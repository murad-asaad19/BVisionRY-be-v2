package com.bvisionry.coaching.web;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.coaching.domain.CoachAvailabilityBlock;
import com.bvisionry.coaching.domain.CoachAvailabilityRule;
import com.bvisionry.coaching.domain.CoachProfile;
import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.dto.CoachAvailabilityResponse;
import com.bvisionry.coaching.dto.CoachAvailabilityResponse.AvailabilityBlockDto;
import com.bvisionry.coaching.dto.CoachAvailabilityResponse.AvailabilityRuleDto;
import com.bvisionry.coaching.dto.CoachSessionsResponse;
import com.bvisionry.coaching.dto.CoachSessionsResponse.CoachSessionDto;
import com.bvisionry.coaching.dto.CoachSessionsResponse.SessionAttendeeDto;
import com.bvisionry.coaching.dto.UpsertAvailabilityRequest;
import com.bvisionry.coaching.repository.CoachAvailabilityBlockRepository;
import com.bvisionry.coaching.repository.CoachAvailabilityRuleRepository;
import com.bvisionry.coaching.repository.CoachProfileRepository;
import com.bvisionry.coaching.repository.CoachingBookingRepository;
import com.bvisionry.coaching.repository.CoachingBookingRepository.SessionMemberRow;
import com.bvisionry.coaching.repository.CoachingBookingRepository.SessionRow;
import com.bvisionry.common.event.CoachingEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUserAccessor;

import lombok.RequiredArgsConstructor;

/**
 * The coach's side of the calendar (spec v2 §6.1): the availability they
 * publish and the sessions that land on it.
 *
 * <p>Cohort-wide scheduling is NOT here. A group session's coach, dates and
 * roster are one decision that a super admin makes too, so it lives in
 * {@link CohortSessionSchedulingService} and this class delegates the one verb
 * they share (cancel) to it — rather than growing a second copy that would
 * drift from the admin's.
 *
 * <p>The caller's identity IS the scope. Availability is keyed on the
 * authenticated principal — no id in the path, so no colleague's row to reach —
 * and every booking read/write carries {@code coach_id = caller} into the SQL,
 * so "not mine" and "already resolved" collapse into the same answer instead of
 * being two checks that could drift.
 */
@Service
@RequiredArgsConstructor
public class CoachScheduleService {

    /**
     * The to-do bucket reads by task, and within a task the session the coach
     * must date comes before the ones they are waiting on a founder for
     * (spec §6.1). A cohort-wide row has no member name, which is exactly why
     * NULLS FIRST puts it at the head of its task.
     */
    private static final Comparator<CoachSessionDto> UNSCHEDULED_ORDER =
            Comparator.comparing(CoachSessionDto::taskName,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(CoachSessionDto::memberName,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    /** The wire format for a weekly window's times (spec §6.1). */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final CoachAvailabilityRuleRepository rules;
    private final CoachAvailabilityBlockRepository blocks;
    private final CoachProfileRepository profiles;
    private final CoachingBookingRepository bookings;
    private final CohortSessionSchedulingService cohortScheduling;
    private final CurrentUserAccessor currentUser;
    private final ApplicationEventPublisher events;

    /* ---------------------------------------------------------- availability */

    @Transactional(readOnly = true)
    public CoachAvailabilityResponse availability() {
        return read(currentUser.require().userId());
    }

    /**
     * Whole-calendar replace. Validation lives here rather than on the request
     * record because three of the four rules are relational — a zone id must
     * RESOLVE, a window must end after it starts, and two windows on one
     * weekday must not overlap — and bean validation judges one field at a time.
     *
     * <p>Delete-then-insert rather than a diff: the editor sends a week, not
     * row identities, so there is nothing to match old rows against. The whole
     * thing is one transaction, so a coach is never briefly un-bookable.
     */
    @Transactional
    public CoachAvailabilityResponse replaceAvailability(UpsertAvailabilityRequest request) {
        UUID coachId = currentUser.require().userId();
        ZoneId zone = parseZone(request.timeZone());

        List<CoachAvailabilityRule> parsed = new ArrayList<>();
        for (UpsertAvailabilityRequest.RuleUpsert r : request.rules()) {
            LocalTime start = parseTime(r.startTime());
            LocalTime end = parseTime(r.endTime());
            if (!end.isAfter(start)) {
                throw new BadRequestException(
                        "A window must end after it starts: " + r.startTime() + "–" + r.endTime());
            }
            CoachAvailabilityRule rule = new CoachAvailabilityRule();
            rule.setCoachId(coachId);
            rule.setWeekday((short) r.weekday());
            rule.setStartTime(start);
            rule.setEndTime(end);
            parsed.add(rule);
        }
        assertNoOverlaps(parsed);

        List<CoachAvailabilityBlock> parsedBlocks = new ArrayList<>();
        for (UpsertAvailabilityRequest.BlockUpsert b : request.blocks()) {
            if (!b.endsAt().isAfter(b.startsAt())) {
                throw new BadRequestException("Time off must end after it starts.");
            }
            CoachAvailabilityBlock block = new CoachAvailabilityBlock();
            block.setCoachId(coachId);
            block.setStartsAt(b.startsAt().atOffset(ZoneOffset.UTC));
            block.setEndsAt(b.endsAt().atOffset(ZoneOffset.UTC));
            block.setReason(b.reason() == null || b.reason().isBlank() ? null : b.reason().trim());
            parsedBlocks.add(block);
        }

        // The profile row may not exist yet — a coach can publish availability
        // without ever having filled in a bio. Same upsert shape as
        // CoachConsoleService.updateProfile.
        CoachProfile profile = profiles.findById(coachId).orElseGet(() -> {
            CoachProfile fresh = new CoachProfile();
            fresh.setCoachId(coachId);
            return fresh;
        });
        profile.setTimeZone(zone.getId());
        profiles.saveAndFlush(profile);

        rules.deleteByCoachId(coachId);
        blocks.deleteByCoachId(coachId);
        // Flush the deletes before the inserts: JPA would otherwise order the
        // inserts first and the bulk delete would take the new rows with it.
        rules.flush();
        blocks.flush();
        rules.saveAll(parsed);
        blocks.saveAll(parsedBlocks);
        rules.flush();
        blocks.flush();
        return read(coachId);
    }

    private CoachAvailabilityResponse read(UUID coachId) {
        return new CoachAvailabilityResponse(
                profiles.findById(coachId).map(CoachProfile::getTimeZone).orElse(null),
                rules.findByCoachIdOrderByWeekdayAscStartTimeAsc(coachId).stream()
                        .map(r -> new AvailabilityRuleDto(r.getId(), r.getWeekday(),
                                HH_MM.format(r.getStartTime()), HH_MM.format(r.getEndTime())))
                        .toList(),
                blocks.findByCoachIdOrderByStartsAtAsc(coachId).stream()
                        .map(b -> new AvailabilityBlockDto(b.getId(), b.getStartsAt().toInstant(),
                                b.getEndsAt().toInstant(), b.getReason()))
                        .toList());
    }

    /**
     * Two windows on the same weekday may not overlap. Not a database
     * constraint because the natural one (an exclusion over a time range per
     * weekday) would still miss the point: the slot engine chunks each window
     * from ITS OWN start, so overlapping windows silently produce duplicate and
     * misaligned slots rather than an error the coach can see.
     */
    private static void assertNoOverlaps(List<CoachAvailabilityRule> parsed) {
        List<CoachAvailabilityRule> sorted = new ArrayList<>(parsed);
        sorted.sort(Comparator.comparing(CoachAvailabilityRule::getWeekday)
                .thenComparing(CoachAvailabilityRule::getStartTime));
        for (int i = 1; i < sorted.size(); i++) {
            CoachAvailabilityRule prev = sorted.get(i - 1);
            CoachAvailabilityRule next = sorted.get(i);
            if (prev.getWeekday().equals(next.getWeekday())
                    && next.getStartTime().isBefore(prev.getEndTime())) {
                throw new BadRequestException(
                        "Two availability windows overlap on the same day.");
            }
        }
    }

    private static ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw.trim());
        } catch (RuntimeException e) {
            throw new BadRequestException("Not a valid time zone: " + raw);
        }
    }

    private static LocalTime parseTime(String raw) {
        try {
            return LocalTime.parse(raw.trim(), HH_MM);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Times must look like 09:00 — got: " + raw);
        }
    }
    /* -------------------------------------------------------------- sessions */

    /**
     * The coach's console (spec v2 §6.1), in three buckets.
     *
     * <p>{@code unscheduled} is the only one that is a TO-DO rather than a
     * record: a cohort-wide row waiting for the coach to date it, or a 1:1 row
     * waiting for its member to book. {@code past} counts a session that has
     * merely STARTED, not one that was marked — that is what puts a
     * still-SCHEDULED row in front of the coach so they can take the roll call.
     */
    @Transactional(readOnly = true)
    public CoachSessionsResponse sessions() {
        UUID coachId = currentUser.require().userId();
        List<SessionRow> rows = bookings.findCoachSessions(currentUser.require().orgId(), coachId);
        Map<UUID, List<SessionAttendeeDto>> attendees = bookings.sessionMembers(
                        rows.stream().map(SessionRow::sessionId).toList()).stream()
                .collect(Collectors.groupingBy(SessionMemberRow::sessionId, LinkedHashMap::new,
                        Collectors.mapping(m -> new SessionAttendeeDto(m.memberId(), m.name(),
                                m.present()), Collectors.toList())));

        Instant now = Instant.now();
        List<CoachSessionDto> unscheduled = new ArrayList<>();
        List<CoachSessionDto> upcoming = new ArrayList<>();
        List<CoachSessionDto> past = new ArrayList<>();
        for (SessionRow row : rows) {
            CoachSessionDto dto = asDto(row, attendees.getOrDefault(row.sessionId(), List.of()));
            if (row.isUnscheduled()) {
                unscheduled.add(dto);
            } else if (row.isScheduled() && row.startsAt().isAfter(now)) {
                upcoming.add(dto);
            } else {
                past.add(dto);
            }
        }
        // The SQL cannot order all three at once: a to-do list reads by WHAT it
        // is, a diary by WHEN. So the list query orders by date (which already
        // leaves `upcoming` ascending) and the two buckets that want another
        // order say so here.
        unscheduled.sort(UNSCHEDULED_ORDER);
        past.sort(Comparator.comparing(CoachSessionDto::startsAt).reversed());
        return new CoachSessionsResponse(unscheduled, upcoming, past);
    }

    /**
     * The slots the CALLER can offer for a cohort-wide session they may
     * schedule (spec v2 §6.1). Delegated whole: the coach route never names a
     * coach, so the scheduling service resolves it to the caller.
     */
    @Transactional(readOnly = true)
    public BookingSlotsResponse sessionSlots(UUID sessionId, Instant from, Instant to) {
        return cohortScheduling.slots(sessionId, null, from, to);
    }

    /** Date (or re-date) a cohort-wide session on the caller's own calendar. */
    @Transactional
    public void scheduleSession(UUID sessionId, Instant startsAt) {
        cohortScheduling.schedule(sessionId, null, startsAt);
    }

    /**
     * The roll call, submitted whole: the session becomes COMPLETED with
     * EXACTLY {@code presentMemberIds} marked present. Replacing rather than
     * merging is what makes the coach's screen the truth — an unchecked box has
     * to be able to REMOVE a mark the auto-complete job wrote.
     *
     * <p>A session that has not started yet cannot be marked (400): "held" is a
     * claim about the past, and the coach's own list is what would otherwise
     * let a mis-click resolve tomorrow's session.
     */
    @Transactional
    public void complete(UUID sessionId, List<UUID> presentMemberIds) {
        UUID coachId = currentUser.require().userId();
        SessionRow row = requireOwnSession(sessionId, coachId);
        Instant now = Instant.now();
        if (row.startsAt() == null || row.startsAt().isAfter(now)) {
            throw new BadRequestException("That session hasn't started yet.");
        }
        List<SessionMemberRow> members = bookings.sessionMembers(sessionId);
        Set<UUID> present = Set.copyOf(presentMemberIds);
        if (!members.stream().map(SessionMemberRow::memberId).collect(Collectors.toSet())
                .containsAll(present)) {
            throw new BadRequestException("Someone on that list isn't expected at this session.");
        }
        // Re-submitting is not an error: the auto-complete job may have marked
        // the row already, and the coach's roll call has to be able to overrule
        // it. The survey invitation can therefore re-fire for a member who has
        // not answered yet — a duplicate nudge, not a duplicate response.
        bookings.complete(sessionId, now);
        bookings.clearAttendance(sessionId);
        for (SessionMemberRow member : members) {
            if (present.contains(member.memberId())) {
                bookings.markPresent(sessionId, member.memberId(), coachId, now);
                publishCompleted(row, member);
            }
        }
    }

    /**
     * Correct ONE mark after the fact (spec §6.1) — the auto-complete job marks
     * everybody present, so this is how the coach records who really missed it,
     * and how a late correction turns into the feedback invitation that was
     * never sent.
     */
    @Transactional
    public void setAttendance(UUID sessionId, UUID memberId, boolean present) {
        UUID coachId = currentUser.require().userId();
        SessionRow row = requireOwnSession(sessionId, coachId);
        if (!row.isCompleted()) {
            throw new IllegalOperationException("That session hasn't been marked held yet.");
        }
        SessionMemberRow member = bookings.sessionMembers(sessionId).stream()
                .filter(m -> m.memberId().equals(memberId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "That member isn't expected at this session."));
        if (!present) {
            bookings.markAbsent(sessionId, memberId);
            return;
        }
        if (!member.present()) {
            bookings.markPresent(sessionId, memberId, coachId, Instant.now());
            publishCompleted(row, member);
        }
    }

    /**
     * A missed 1:1 goes back to UNSCHEDULED so the member can rebook (spec
     * §6.1). Every condition — COMPLETED, per-member, nobody marked present —
     * lives in the UPDATE, so this cannot un-hold a session someone attended.
     */
    @Transactional
    public void reopen(UUID sessionId) {
        UUID coachId = currentUser.require().userId();
        SessionRow row = requireOwnSession(sessionId, coachId);
        if (!bookings.reopen(sessionId)) {
            throw new IllegalOperationException(
                    "Only a 1:1 that was held with nobody present can be reopened.");
        }
        // The row lost its dates and its coach, so the Google event has to go
        // the same way a cancellation's does — same event, same handler.
        events.publishEvent(cancelled(row, true));
    }

    /**
     * Coach-side cancel — the row reverts to UNSCHEDULED, it is never deleted.
     * A cohort-wide session belongs to the scheduling service, which owns the
     * roster its cancellation event carries, so only the 1:1 case is handled
     * here.
     */
    @Transactional
    public void cancel(UUID sessionId) {
        UUID coachId = currentUser.require().userId();
        SessionRow row = bookings.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId.toString()));
        if (row.isCohortWide()) {
            cohortScheduling.cancel(sessionId, true);
            return;
        }
        requireOwnSession(sessionId, coachId);
        if (!bookings.revertToUnscheduled(sessionId)) {
            throw new IllegalOperationException("That session is not scheduled.");
        }
        events.publishEvent(cancelled(row, true));
    }

    /* ------------------------------------------------------------- internals */

    private void publishCompleted(SessionRow row, SessionMemberRow member) {
        CoachingEvents.SessionCompleted event = completed(row, member);
        if (event != null) {
            events.publishEvent(event);
        }
    }

    /**
     * A session that is not this coach's is a 404, not a 403: the coach has no
     * business learning that someone else's session id exists.
     */
    private SessionRow requireOwnSession(UUID sessionId, UUID coachId) {
        return bookings.findById(sessionId)
                .filter(r -> coachId.equals(r.coachId()))
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId.toString()));
    }

    /**
     * Spec §1.4: the survey invitation goes out once the session is held AND
     * the member was there AND the task names a survey AND they have not
     * already answered it. Filtered HERE — shared with
     * {@code SessionAutoCompleteJob}, which holds the same four facts — so the
     * two paths cannot gate the invitation differently.
     *
     * @return null when the invitation is not owed
     */
    static CoachingEvents.SessionCompleted completed(SessionRow row, SessionMemberRow member) {
        if (row.feedbackSurveyId() == null || member.feedbackSubmitted()) {
            return null;
        }
        return new CoachingEvents.SessionCompleted(
                row.sessionId(), member.memberId(), member.name(), member.email(),
                row.coachName(), row.taskId(), row.taskName(), row.cohortId(),
                row.feedbackSurveyId());
    }

    /** Shared with {@code MemberBookingService} through the event, not through code. */
    static CoachingEvents.SessionCancelled cancelled(SessionRow row, boolean byCoach) {
        return new CoachingEvents.SessionCancelled(row.sessionId(), row.memberId(), row.coachId(),
                row.memberName(), row.memberEmail(), row.coachName(), row.coachEmail(),
                row.coachTimeZone(), row.startsAt(), row.endsAt(), row.taskId(), row.taskName(),
                row.cohortId(), row.cohortName(), byCoach);
    }

    /**
     * {@code canSchedule} needs no extra column: the list query already admits a
     * cohort-wide row only when the caller holds it or may schedule for its
     * cohort, so "cohort-wide and not yet held" is the whole condition. The
     * write path re-checks the grant itself.
     */
    private static CoachSessionDto asDto(SessionRow row, List<SessionAttendeeDto> attendees) {
        return new CoachSessionDto(row.sessionId(), row.sessionType(),
                row.memberId(), row.memberName(),
                row.cohortId(), row.cohortName(), row.taskId(), row.taskName(),
                row.startsAt(), row.endsAt(),
                row.durationMinutes() == null ? 0 : row.durationMinutes(),
                row.bookingStatus(), row.completedAt(), row.meetingUrl(), row.isCohortWide() && !row.isCompleted(), attendees,
                row.feedbackSubmitted());
    }
}
