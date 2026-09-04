package com.bvisionry.coaching.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.dto.MemberBookingResponse;
import com.bvisionry.coaching.dto.MemberBookingResponse.BookingCoachDto;
import com.bvisionry.coaching.dto.MemberBookingResponse.BookingDto;
import com.bvisionry.coaching.repository.CoachAvailabilityRuleRepository;
import com.bvisionry.coaching.repository.CoachingBookingRepository;
import com.bvisionry.coaching.repository.CoachingBookingRepository.SessionRow;
import com.bvisionry.coaching.repository.CoachingBookingRepository.SessionTaskRow;
import com.bvisionry.coaching.repository.CoachingReadRepository;
import com.bvisionry.coaching.repository.CoachingReadRepository.CoachOfMemberRow;
import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

import lombok.RequiredArgsConstructor;

/**
 * The member's side of a SESSION task (spec v2 §6.3): see the task, see the
 * slots, book one, move it, cancel it.
 *
 * <p>Nothing in a request names the caller — the task comes from the path, the
 * member from the session — and the task read carries the enrollment +
 * audience predicates in SQL, so a task that is not theirs is a 404 rather than
 * a permission check that could be forgotten.
 *
 * <p><strong>Booking is an UPDATE, not an INSERT (spec §2.3).</strong> The row
 * already exists, UNSCHEDULED, put there by {@code SessionMaterializer} when
 * the cohort reached the task; booking dates it and cancelling un-dates it. The
 * insert path below exists only for the case where sync has not run yet, and it
 * writes exactly the row the materialiser would have.
 *
 * <p><strong>Only a 1:1 is booked here.</strong> A group or workshop task is
 * dated by the cohort's coach or a super admin
 * ({@link CohortSessionSchedulingService}); the member's three write routes
 * answer 400 on one, because "book" is not a thing that exists for them.
 *
 * <p><strong>The slot is re-derived, never trusted.</strong> A POST names an
 * instant; {@link CoachSlots} recomputes the coach's offer around that instant
 * and refuses anything the engine did not produce. Behind that, the database's
 * exclusion constraint is the actual race guard.
 */
@Service
@RequiredArgsConstructor
public class MemberBookingService {

    private final CoachingBookingRepository sessions;
    private final CoachingReadRepository reads;
    private final CoachAvailabilityRuleRepository rules;
    private final CoachSlots slots;
    private final MediaUrlPort mediaUrlPort;
    private final CurrentUserAccessor currentUser;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public MemberBookingResponse booking(UUID taskId) {
        CurrentUser caller = currentUser.require();
        return booking(requireTask(taskId, caller.userId()),
                sessions.findMemberSession(taskId, caller.userId()));
    }

    /**
     * The same response off rows the caller already holds — the write paths end
     * here rather than re-running the task read, the session read and the coach
     * list a second time inside their transaction.
     */
    private MemberBookingResponse booking(SessionTaskRow task, Optional<SessionRow> row) {
        CurrentUser caller = currentUser.require();
        return new MemberBookingResponse(
                task.taskId(), task.taskName(), task.sessionType(), task.durationMinutes(),
                row.map(MemberBookingService::asDto).orElse(null),
                // "You and N others" — the roll call of the cohort-wide row is
                // already the active-learner roster (V163), so it is the count.
                task.isCohortWide()
                        ? row.map(r -> sessions.sessionMemberCount(r.sessionId())).orElse(null)
                        : null,
                // Nothing for the member to pick on a cohort-wide task: their
                // coach or the platform dates it (spec §6.3).
                task.isCohortWide() ? List.of() : bookableCoaches(caller),
                task.postSessionSurveyId(),
                row.filter(SessionRow::isCompleted)
                        .filter(SessionRow::attended)
                        .filter(b -> task.postSessionSurveyId() != null)
                        .filter(b -> !b.feedbackSubmitted())
                        .isPresent());
    }

    /** The coach's free instants in {@code [from, to)} — 1:1 tasks only. */
    @Transactional(readOnly = true)
    public BookingSlotsResponse slots(UUID taskId, UUID coachId, Instant from, Instant to) {
        CurrentUser caller = currentUser.require();
        SessionTaskRow task = requireTask(taskId, caller.userId());
        requireBookable(task);
        requireCoachOfMember(caller, coachId);
        return slots.offer(coachId, requireDuration(task.durationMinutes()), from, to,
                sessions.findMemberSession(taskId, caller.userId())
                        .map(SessionRow::sessionId).orElse(null));
    }

    /**
     * Book: date the member's own UNSCHEDULED row. The refusals are ordered
     * cheapest-first, and the last one — "the engine did not offer this
     * instant" — is what stops a crafted POST from landing outside the coach's
     * published hours.
     */
    @Transactional
    public MemberBookingResponse book(UUID taskId, UUID coachId, Instant startsAt) {
        CurrentUser caller = currentUser.require();
        SessionTaskRow task = requireTask(taskId, caller.userId());
        requireBookable(task);
        requireLaunched(task);
        requireCoachOfMember(caller, coachId);

        Optional<SessionRow> existing = sessions.findMemberSession(taskId, caller.userId());
        if (existing.filter(r -> !r.isUnscheduled()).isPresent()) {
            throw new DuplicateResourceException("You already have a session booked for this task.");
        }
        int minutes = requireDuration(task.durationMinutes());
        slots.requireOffered(coachId, minutes, startsAt);
        try {
            // Materialisation may not have reached this member (a board saved
            // before the listener ran, or a race with it). Same statement the
            // materialiser uses, so the two cannot write different rows.
            UUID sessionId = existing.map(SessionRow::sessionId)
                    .orElseGet(() -> sessions.materializeOwnSession(task.cohortId(), taskId,
                            task.taskName(), task.sessionType(), caller.userId()));
            if (!sessions.schedule(sessionId, coachId, startsAt,
                    startsAt.plus(Duration.ofMinutes(minutes)))) {
                throw new DuplicateResourceException(
                        "You already have a session booked for this task.");
            }
        } catch (DataIntegrityViolationException e) {
            // ux_sessions_task_member vs ex_sessions_coach_no_overlap — the two
            // races have different answers for the member, so tell them apart
            // by the constraint that actually fired.
            throw new DuplicateResourceException(
                    String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_sessions_task_member")
                            ? "You already have a session booked for this task."
                            : "That slot was just taken.");
        }
        Optional<SessionRow> booked = sessions.findMemberSession(taskId, caller.userId());
        booked.ifPresent(row ->
                events.publishEvent(new CoachingEvents.SessionBooked(row.sessionId(), row.memberId(),
                        row.coachId(), row.memberName(), row.memberEmail(), row.coachName(),
                        row.coachEmail(), row.coachTimeZone(), row.startsAt(), row.endsAt(),
                        row.taskId(), row.taskName(), row.cohortId(), row.cohortName())));
        return booking(task, booked);
    }

    /**
     * Move own 1:1 to another coach/slot in ONE step (spec §6.3) — the UI's
     * Reschedule, which is deliberately NOT a DELETE followed by a POST.
     *
     * <p>The pair would free the old slot for the seconds between the two
     * writes, delete and re-create the coach's calendar event, and send the
     * founder a cancellation followed by a confirmation for a session that never
     * stopped existing. One UPDATE, one {@code SessionRescheduled}, one calendar
     * PATCH instead.
     *
     * <p>Same refusals as {@link #book}, plus the state guard: only a SCHEDULED
     * session of the caller's that has not started yet may move.
     */
    @Transactional
    public MemberBookingResponse reschedule(UUID taskId, UUID coachId, Instant startsAt) {
        CurrentUser caller = currentUser.require();
        SessionTaskRow task = requireTask(taskId, caller.userId());
        requireBookable(task);
        requireLaunched(task);
        requireCoachOfMember(caller, coachId);

        SessionRow row = sessions.findMemberSession(taskId, caller.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Session", taskId.toString()));
        if (!row.isScheduled() || !row.startsAt().isAfter(Instant.now())) {
            throw new IllegalOperationException("That session can no longer be moved.");
        }
        // The picker offers the slot the session already holds (it does not make
        // itself busy), so "moving" to it is a real click — and re-writing the
        // same values would mail a reschedule that did not happen.
        if (coachId.equals(row.coachId()) && startsAt.equals(row.startsAt())) {
            return booking(task, Optional.of(row));
        }

        int minutes = requireDuration(task.durationMinutes());
        slots.requireOffered(coachId, minutes, startsAt, row.sessionId());
        try {
            if (!sessions.reschedule(row.sessionId(), coachId, startsAt,
                    startsAt.plus(Duration.ofMinutes(minutes)))) {
                throw new IllegalOperationException("That session can no longer be moved.");
            }
        } catch (DataIntegrityViolationException e) {
            // Only ex_sessions_coach_no_overlap can fire here: the row already
            // exists, so the one-per-(task, member) index is untouched.
            throw new DuplicateResourceException("That slot was just taken.");
        }
        Optional<SessionRow> moved = sessions.findMemberSession(taskId, caller.userId());
        moved.ifPresent(fresh ->
                events.publishEvent(new CoachingEvents.SessionRescheduled(fresh.sessionId(),
                        fresh.memberId(), fresh.coachId(), fresh.memberName(), fresh.memberEmail(),
                        fresh.coachName(), fresh.coachEmail(), fresh.coachTimeZone(),
                        row.startsAt(), row.endsAt(), fresh.startsAt(), fresh.endsAt(),
                        fresh.taskId(), fresh.taskName(), fresh.cohortId(), fresh.cohortName())));
        return booking(task, moved);
    }

    /**
     * Cancel own session — back to UNSCHEDULED, never deleted, so the journey
     * row keeps its identity and they can rebook. Only SCHEDULED and only
     * before it starts: once the clock passes the start the session is HELD as
     * far as participation scoring is concerned, and un-holding it is the
     * coach's call.
     */
    @Transactional
    public void cancel(UUID taskId) {
        CurrentUser caller = currentUser.require();
        SessionTaskRow task = requireTask(taskId, caller.userId());
        requireBookable(task);
        requireLaunched(task);
        SessionRow row = sessions.findMemberSession(taskId, caller.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Session", taskId.toString()));
        if (!row.isScheduled() || !row.startsAt().isAfter(Instant.now())) {
            throw new IllegalOperationException("That session can no longer be cancelled.");
        }
        sessions.revertToUnscheduled(row.sessionId());
        events.publishEvent(CoachScheduleService.cancelled(row, false));
    }

    /* ------------------------------------------------------------- internals */

    private SessionTaskRow requireTask(UUID taskId, UUID userId) {
        return sessions.findEnrolledSessionTask(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Session task", taskId.toString()));
    }

    /**
     * Spec §6.3: a group or workshop session is not the member's to date. The
     * copy is the answer the screen shows, so the 400 is informative rather
     * than a bare refusal.
     */
    private static void requireBookable(SessionTaskRow task) {
        if (task.isCohortWide()) {
            throw new BadRequestException("This session is scheduled by your coach.");
        }
    }

    private static void requireLaunched(SessionTaskRow task) {
        if (!"LAUNCHED".equals(task.cohortStatus())) {
            throw new BadRequestException("This cohort is read-only now.");
        }
    }

    /**
     * Spec §5: the coach must be one the member may actually book — the
     * assignment union, narrowed to coaches who have published availability.
     * Checked against the same list the GET hands the picker, so the UI can
     * never offer a coach the POST would refuse.
     */
    private void requireCoachOfMember(CurrentUser caller, UUID coachId) {
        if (bookableCoaches(caller).stream().noneMatch(c -> c.id().equals(coachId))) {
            throw new BadRequestException("That coach isn't available to you.");
        }
    }

    private List<BookingCoachDto> bookableCoaches(CurrentUser caller) {
        List<CoachOfMemberRow> all = reads.coachesOfMember(caller.orgId(), caller.userId());
        if (all.isEmpty()) {
            return List.of();
        }
        Set<UUID> bookable = Set.copyOf(rules.coachIdsWithRules(
                all.stream().map(CoachOfMemberRow::id).collect(Collectors.toSet())));
        return all.stream()
                .filter(c -> bookable.contains(c.id()))
                // The photo leaves the column as a minio:// marker, which no
                // browser can load — resolve it here, as FounderCoachController does.
                .map(c -> new BookingCoachDto(c.id(), c.name(), c.headline(),
                        mediaUrlPort.resolveUrl(c.photoUrl())))
                .toList();
    }

    /**
     * Duration is required for a SESSION task to go LIVE, but the column is
     * nullable for every other type — treat a missing one as a misconfigured
     * task rather than silently booking a zero-length session. Shared with
     * {@link CohortSessionSchedulingService}, which schedules off the session
     * row rather than the task but owes the same answer.
     */
    static int requireDuration(Integer minutes) {
        if (minutes == null) {
            throw new BadRequestException("This session has no duration configured yet.");
        }
        return minutes;
    }

    /**
     * {@code attended} is null until the session is held: "we do not know yet"
     * and "they were not there" are different answers, and the journey row
     * renders Missed off the second one alone (spec §3).
     */
    private static BookingDto asDto(SessionRow row) {
        return new BookingDto(row.sessionId(), row.coachId(), row.coachName(), row.startsAt(),
                row.endsAt(), row.bookingStatus(), row.completedAt(), row.meetingUrl(),
                row.isCompleted() ? row.attended() : null);
    }
}
