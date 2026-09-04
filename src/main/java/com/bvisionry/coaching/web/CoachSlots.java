package com.bvisionry.coaching.web;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bvisionry.coaching.domain.CoachProfile;
import com.bvisionry.coaching.domain.SlotEngine;
import com.bvisionry.coaching.dto.BookingSlotsResponse;
import com.bvisionry.coaching.repository.CoachAvailabilityBlockRepository;
import com.bvisionry.coaching.repository.CoachAvailabilityRuleRepository;
import com.bvisionry.coaching.repository.CoachProfileRepository;
import com.bvisionry.coaching.repository.CoachingBookingRepository;
import com.bvisionry.common.calendar.CalendarBusyPort;
import com.bvisionry.common.calendar.TimeRange;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;

import lombok.RequiredArgsConstructor;

/**
 * "What may this coach be booked at?" — the one place the answer is computed,
 * shared by all three callers of it (the member's 1:1 picker, the coach's own
 * cohort-session picker and the super admin's).
 *
 * <p>Three surfaces asking the same question is exactly how a picker starts
 * offering an instant the POST then refuses, so the OFFER and the CHECK live
 * next to each other here rather than being restated per service.
 *
 * <p><strong>Busy is a union of two sources (spec §6).</strong> Our own
 * SCHEDULED rows, which the database also guards with
 * {@code ex_sessions_coach_no_overlap}, plus the coach's REAL calendar through
 * {@link CalendarBusyPort}. The second half is why {@link #requireOffered}
 * checks it explicitly: the exclusion constraint cannot see Google, so an
 * instant that is free in our schema but taken on the coach's own calendar
 * would otherwise be booked over a real appointment.
 */
@Component
@RequiredArgsConstructor
public class CoachSlots {

    /** Spec §6: nobody books further out than this, and no range is wider. */
    public static final Duration BOOKING_HORIZON = Duration.ofDays(56);

    private final CoachAvailabilityRuleRepository rules;
    private final CoachAvailabilityBlockRepository blocks;
    private final CoachProfileRepository profiles;
    private final CoachingBookingRepository sessions;
    private final CalendarBusyPort calendarBusy;

    /**
     * The coach's free instants in {@code [from, to)}. The window is capped at
     * the booking horizon — an unbounded range would expand every weekly rule
     * over it, so the cap is a cost guard as much as a product rule.
     *
     * <p>No published zone means the coach has never saved availability, so
     * there is nothing to expand: an empty offer, not an error.
     *
     * @param forSessionId the row this offer is FOR, so a session already in the
     *                     calendar does not hide the slot it is being moved off
     *                     (spec §6.3 reschedule); null when there is no row yet
     */
    public BookingSlotsResponse offer(UUID coachId, int durationMinutes, Instant from, Instant to,
                                      UUID forSessionId) {
        if (!from.isBefore(to)) {
            throw new BadRequestException("The end of the range must be after its start.");
        }
        if (Duration.between(from, to).compareTo(BOOKING_HORIZON) > 0) {
            throw new BadRequestException("Slots can only be listed 8 weeks at a time.");
        }
        String zoneId = zoneOf(coachId);
        if (zoneId == null) {
            return new BookingSlotsResponse(null, List.of());
        }
        return new BookingSlotsResponse(zoneId, slots(coachId, ZoneId.of(zoneId), durationMinutes,
                from, to, busy(coachId, from, to, forSessionId)));
    }

    /** {@link #requireOffered(UUID, int, Instant, UUID)} for a slot with no row yet. */
    public void requireOffered(UUID coachId, int durationMinutes, Instant startsAt) {
        requireOffered(coachId, durationMinutes, startsAt, null);
    }

    /**
     * Refuse an instant beyond the booking horizon (400), one the coach did not
     * publish (400), or one their real calendar already holds (409).
     *
     * <p>The horizon is checked HERE and not only in {@link #offer}: the picker
     * caps the range it lists, but a POST carries a bare instant, and without
     * this guard a hand-made or stale request dates a session years out — past
     * every reminder window and every availability rule the coach will ever
     * revise.
     *
     * <p>NO internal busy ranges here, deliberately — v1's stance, kept. What
     * the first check owns is "is this inside the coach's published hours at
     * all"; whether one of OUR sessions already holds it is a race no read can
     * settle, and {@code ex_sessions_coach_no_overlap} settles it with the 409
     * the spec asks for. Subtracting our own bookings here would only turn that
     * 409 into a misleading 400 whenever the read happened to be fresh enough.
     * The EXTERNAL calendar has no such constraint behind it, so it is checked.
     *
     * <p>A one-day window either side: the engine chunks from each window's own
     * start, so asking for exactly {@code [startsAt, startsAt+duration]} would
     * only ever match a slot that happens to align with the range itself.
     */
    public void requireOffered(UUID coachId, int durationMinutes, Instant startsAt,
                               UUID forSessionId) {
        if (startsAt.isAfter(Instant.now().plus(BOOKING_HORIZON))) {
            throw new BadRequestException("Sessions can only be booked 8 weeks ahead.");
        }
        String zoneId = zoneOf(coachId);
        if (zoneId == null || !slots(coachId, ZoneId.of(zoneId), durationMinutes,
                startsAt.minus(Duration.ofDays(1)), startsAt.plus(Duration.ofDays(1)), List.of())
                .contains(startsAt)) {
            throw new BadRequestException("That slot is not offered by this coach.");
        }
        Instant endsAt = startsAt.plus(Duration.ofMinutes(durationMinutes));
        for (TimeRange range : externalBusy(coachId, startsAt, endsAt, forSessionId)) {
            if (startsAt.isBefore(range.end()) && endsAt.isAfter(range.start())) {
                throw new IllegalOperationException("That time is busy on the coach's calendar.");
            }
        }
    }

    /** The coach's IANA zone, or null until they first save availability. */
    public String zoneOf(UUID coachId) {
        return profiles.findById(coachId).map(CoachProfile::getTimeZone).orElse(null);
    }

    private List<TimeRange> busy(UUID coachId, Instant from, Instant to, UUID forSessionId) {
        List<TimeRange> busy = new ArrayList<>(sessions.busyRanges(coachId, from, to, forSessionId));
        busy.addAll(externalBusy(coachId, from, to, forSessionId));
        return busy;
    }

    /**
     * The coach's REAL calendar in {@code [from, to)} MINUS the session being
     * moved. Its own Google event still sits on that calendar, so leaving it in
     * would make a reschedule by less than the session's own duration
     * impossible — the same reason {@code busyRanges} excludes the row itself.
     * Nothing else is subtracted: every other appointment is a real conflict.
     */
    private List<TimeRange> externalBusy(UUID coachId, Instant from, Instant to, UUID forSessionId) {
        TimeRange own = forSessionId == null ? null
                : sessions.findById(forSessionId)
                        .filter(r -> r.startsAt() != null && r.endsAt() != null)
                        .map(r -> new TimeRange(r.startsAt(), r.endsAt()))
                        .orElse(null);
        List<TimeRange> out = new ArrayList<>();
        for (TimeRange range : calendarBusy.busy(coachId, from, to)) {
            out.addAll(minus(range, own));
        }
        return out;
    }

    /** {@code range} with {@code cut} removed: 0, 1 or 2 pieces, never wider. */
    static List<TimeRange> minus(TimeRange range, TimeRange cut) {
        if (cut == null || !range.start().isBefore(cut.end()) || !cut.start().isBefore(range.end())) {
            return List.of(range);
        }
        List<TimeRange> out = new ArrayList<>(2);
        if (range.start().isBefore(cut.start())) {
            out.add(new TimeRange(range.start(), cut.start()));
        }
        if (cut.end().isBefore(range.end())) {
            out.add(new TimeRange(cut.end(), range.end()));
        }
        return out;
    }

    private List<Instant> slots(UUID coachId, ZoneId zone, int durationMinutes,
                                Instant from, Instant to, List<TimeRange> busy) {
        return SlotEngine.slots(
                rules.findByCoachIdOrderByWeekdayAscStartTimeAsc(coachId).stream()
                        .map(r -> new SlotEngine.Rule(r.getWeekday(), r.getStartTime(), r.getEndTime()))
                        .toList(),
                blocks.findByCoachIdOrderByStartsAtAsc(coachId).stream()
                        .map(b -> new TimeRange(b.getStartsAt().toInstant(),
                                b.getEndsAt().toInstant()))
                        .toList(),
                busy, zone, durationMinutes, from, to, Instant.now());
    }
}
