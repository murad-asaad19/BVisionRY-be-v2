package com.bvisionry.coaching.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.dto.CohortSessionSchedulingResponse;
import com.bvisionry.coaching.dto.CohortSessionSchedulingResponse.SchedulingCoachDto;
import com.bvisionry.coaching.repository.CoachingBookingRepository;
import com.bvisionry.coaching.repository.CoachingBookingRepository.SessionRow;
import com.bvisionry.coaching.repository.CoachingReadRepository;
import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.common.event.CoachingEvents.Attendee;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

import lombok.RequiredArgsConstructor;

/**
 * Dating a COHORT-WIDE session — group coaching and workshops (spec v2 §6.1
 * and §6.2).
 *
 * <p>ONE service behind TWO controllers. The coach schedules their own
 * calendar; a super admin schedules any coach of the cohort. That is the only
 * difference, and it is one line ({@link #requireMayScheduleAs}) — everything
 * else (the slot re-derivation, the UPDATE, the roster the event carries, the
 * move-in-place reschedule) is identical, and duplicating it per
 * controller is how the two would drift into offering different answers.
 *
 * <p>A 1:1 row never reaches here: the member books it (see
 * {@link MemberBookingService}), and this service refuses one with a 400 rather
 * than silently dating a row behind the founder's back.
 */
@Service
@RequiredArgsConstructor
public class CohortSessionSchedulingService {

    private final CoachingBookingRepository sessions;
    private final CoachingReadRepository reads;
    private final CoachSlots slots;
    private final MediaUrlPort mediaUrlPort;
    private final CurrentUserAccessor currentUser;
    private final ApplicationEventPublisher events;

    /** Spec §6.2: what the admin's dialog needs before it can ask for slots. */
    @Transactional(readOnly = true)
    public CohortSessionSchedulingResponse scheduling(UUID sessionId) {
        SessionRow row = requireCohortWide(sessionId);
        return new CohortSessionSchedulingResponse(row.sessionType(), row.durationMinutes(),
                row.bookingStatus(),
                reads.coachesOfCohort(row.cohortId()).stream()
                        .map(c -> new SchedulingCoachDto(c.id(), c.name(), c.headline(),
                                // The photo leaves the column as a minio:// marker, which no
                                // browser can load — resolve it here, as FounderCoachController does.
                                mediaUrlPort.resolveUrl(c.photoUrl()), c.timeZone()))
                        .toList());
    }

    /**
     * The slots of ONE coach for this session's duration. {@code coachId} null
     * means "the caller's own calendar" — the coach route never names a coach.
     */
    @Transactional(readOnly = true)
    public BookingSlotsResponse slots(UUID sessionId, UUID coachId, Instant from, Instant to) {
        SessionRow row = requireCohortWide(sessionId);
        UUID scheduler = requireMayScheduleAs(row, currentUser.require(), coachId);
        return slots.offer(scheduler, MemberBookingService.requireDuration(row.durationMinutes()),
                from, to, sessionId);
    }

    /**
     * Date the session, or MOVE one that is already dated.
     *
     * <p>A reschedule is one UPDATE and ONE {@code CohortSessionRescheduled} —
     * not the cancel + schedule pair it used to be. The pair told a whole cohort
     * their session was off and then that it was on again, threw the calendar
     * event (and its Meet room) away to make an identical one, and left the old
     * slot claimable by someone else in between.
     *
     * <p>Only a session that has not started yet may move — once it has,
     * un-holding it is {@link #cancel} followed by a fresh schedule, which is a
     * decision, not an edit.
     */
    @Transactional
    public void schedule(UUID sessionId, UUID coachId, Instant startsAt) {
        SessionRow row = requireCohortWide(sessionId);
        CurrentUser caller = currentUser.require();
        UUID scheduler = requireMayScheduleAs(row, caller, coachId);
        int minutes = MemberBookingService.requireDuration(row.durationMinutes());
        Instant endsAt = startsAt.plus(Duration.ofMinutes(minutes));
        List<Attendee> roster = roster(row);

        if (row.isCompleted()) {
            throw new IllegalOperationException("That session has already been held.");
        }
        if (row.isScheduled()) {
            move(row, caller, scheduler, startsAt, endsAt, minutes, roster);
            return;
        }

        slots.requireOffered(scheduler, minutes, startsAt);
        try {
            if (!sessions.schedule(sessionId, scheduler, startsAt, endsAt)) {
                throw new IllegalOperationException("That session was just scheduled by someone else.");
            }
        } catch (DataIntegrityViolationException e) {
            // ex_sessions_coach_no_overlap: the coach's own calendar is the
            // race guard, and losing that race is a 409, not a 400.
            throw new IllegalOperationException("That slot was just taken.");
        }
        sessions.findById(sessionId).ifPresent(fresh ->
                events.publishEvent(new CoachingEvents.CohortSessionScheduled(
                        fresh.sessionId(), fresh.coachId(), fresh.coachName(), fresh.coachEmail(),
                        fresh.coachTimeZone(), fresh.startsAt(), fresh.endsAt(),
                        fresh.taskId(), fresh.taskName(), fresh.cohortId(), fresh.cohortName(),
                        roster)));
    }

    /**
     * The already-SCHEDULED branch of {@link #schedule}: move the row where it
     * stands. A coach may only move what is on their OWN calendar; a super admin
     * may move anyone's, including onto a different coach of the cohort.
     *
     * <p>Moving it onto ANOTHER coach leaves the original event on the first
     * coach's calendar: the sync handler patches by the stored event id, gets a
     * 404 against the new coach's calendar and creates a fresh one (spec §7).
     * Cancel-then-schedule is the clean way to hand a session over; this is
     * noted rather than fixed because a re-assignment is rare and the stale
     * entry is visible to exactly one person, who can delete it.
     */
    private void move(SessionRow row, CurrentUser caller, UUID scheduler,
                      Instant startsAt, Instant endsAt, int minutes, List<Attendee> roster) {
        if (!caller.isSuperAdmin() && !scheduler.equals(row.coachId())) {
            throw new IllegalOperationException("Another coach already scheduled that session.");
        }
        if (!row.startsAt().isAfter(Instant.now())) {
            throw new IllegalOperationException("That session has already started.");
        }
        // The picker offers the slot this session already holds — it does not
        // make itself busy — so "moving" to it is a real click, and re-writing
        // the same values would tell a whole cohort about a change that did not
        // happen.
        if (scheduler.equals(row.coachId()) && startsAt.equals(row.startsAt())) {
            return;
        }
        slots.requireOffered(scheduler, minutes, startsAt, row.sessionId());
        try {
            if (!sessions.reschedule(row.sessionId(), scheduler, startsAt, endsAt)) {
                throw new IllegalOperationException("That session is no longer scheduled.");
            }
        } catch (DataIntegrityViolationException e) {
            throw new IllegalOperationException("That slot was just taken.");
        }
        sessions.findById(row.sessionId()).ifPresent(fresh ->
                events.publishEvent(new CoachingEvents.CohortSessionRescheduled(
                        fresh.sessionId(), fresh.coachId(), fresh.coachName(), fresh.coachEmail(),
                        fresh.coachTimeZone(), row.startsAt(), row.endsAt(),
                        fresh.startsAt(), fresh.endsAt(),
                        fresh.taskId(), fresh.taskName(), fresh.cohortId(), fresh.cohortName(),
                        roster, !caller.isSuperAdmin())));
    }

    /**
     * Cancel a dated cohort-wide session: back to UNSCHEDULED, everyone told.
     *
     * <p>Authorized on the session's OWN coach rather than on
     * {@link #requireMayScheduleAs}: a colleague who also holds the cohort may
     * schedule the sessions nobody has taken, but calling off an appointment
     * someone else's calendar is holding is theirs or the platform's alone.
     */
    @Transactional
    public void cancel(UUID sessionId, boolean byCoach) {
        SessionRow row = requireCohortWide(sessionId);
        CurrentUser caller = currentUser.require();
        if (!caller.isSuperAdmin() && !caller.userId().equals(row.coachId())) {
            throw new AccessDeniedException("That session is not on your calendar.");
        }
        if (!row.isScheduled()) {
            throw new IllegalOperationException("That session is not scheduled.");
        }
        List<Attendee> roster = roster(row);
        sessions.revertToUnscheduled(sessionId);
        events.publishEvent(cancelled(row, roster, byCoach));
    }

    /* ------------------------------------------------------------- internals */

    private SessionRow requireCohortWide(UUID sessionId) {
        SessionRow row = sessions.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId.toString()));
        if (!row.isCohortWide() || row.taskId() == null) {
            throw new BadRequestException("Only a group or workshop session is scheduled this way.");
        }
        return row;
    }

    /**
     * Who this call schedules AS, and whether the caller may.
     *
     * <p>A super admin picks any coach of the cohort; a coach may only ever
     * schedule their own calendar. Either way the resulting coach must hold the
     * cohort (spec §5) — a 403, because unlike a founder's session id, the
     * cohort session is not something the caller was never allowed to know
     * exists: they can see it on the org console.
     */
    private UUID requireMayScheduleAs(SessionRow row, CurrentUser caller, UUID coachId) {
        UUID scheduler = caller.isSuperAdmin() ? coachId : caller.userId();
        if (scheduler == null) {
            throw new BadRequestException("Pick the coach who will run this session.");
        }
        if (!caller.isSuperAdmin() && !scheduler.equals(caller.userId())) {
            throw new AccessDeniedException("You can only schedule your own calendar.");
        }
        if (!reads.isCoachOfCohort(row.cohortId(), scheduler)) {
            throw new AccessDeniedException("That coach isn't assigned to this cohort.");
        }
        return scheduler;
    }

    /**
     * The cohort roster AT SCHEDULING TIME — the calendar event's guest list and
     * the mail fan-out. Carried in the event rather than re-read by the
     * handlers, because a cancellation's handler runs after the row has already
     * lost its dates (spec §8).
     */
    private List<Attendee> roster(SessionRow row) {
        return sessions.sessionMembers(row.sessionId()).stream()
                .map(m -> new Attendee(m.memberId(), m.name(), m.email()))
                .toList();
    }

    private CoachingEvents.CohortSessionCancelled cancelled(SessionRow row, List<Attendee> roster,
                                                            boolean byCoach) {
        return new CoachingEvents.CohortSessionCancelled(row.sessionId(), row.coachId(),
                row.coachName(), row.coachEmail(), row.coachTimeZone(), row.startsAt(), row.endsAt(),
                row.taskId(), row.taskName(), row.cohortId(), row.cohortName(), roster, byCoach);
    }
}
