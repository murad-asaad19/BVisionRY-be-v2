package com.bvisionry.coaching.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import com.bvisionry.common.calendar.TimeRange;

/**
 * "Which instants can this coach be booked at?" — spec §5, expressed as one
 * pure static function so the whole of it is unit-testable without Spring, a
 * database or a clock.
 *
 * <p>It takes plain records rather than the JPA entities next to it for exactly
 * that reason: the engine must not need a persistence context to answer, and
 * the same shape is fed by rows read through raw SQL (busy bookings), by
 * entities (blocks) and by the coach's real calendar
 * ({@link com.bvisionry.common.calendar.TimeRange}, which is why blackouts and
 * bookings are that record rather than a second one saying the same thing).
 *
 * <p><strong>The database, not this class, is the race guard.</strong> The
 * exclusion constraint {@code ex_sessions_coach_no_overlap} decides who wins a
 * contested slot; the engine only decides what to OFFER, so a slot going stale
 * between the picker and the POST is a 409, never a double-booking.
 */
public final class SlotEngine {

    private SlotEngine() {}

    /** Spec §5: nothing bookable inside the next 12 hours. */
    public static final Duration MIN_NOTICE = Duration.ofHours(12);

    /** A weekly window in the coach's own wall clock. {@code weekday}: ISO 1 = Monday. */
    public record Rule(int weekday, LocalTime startTime, LocalTime endTime) {}

    /**
     * Ascending, distinct slot starts inside {@code [from, to)}.
     *
     * <p>Days are walked in {@code zone} and each matching rule is expanded as
     * WALL CLOCK ({@link ZonedDateTime}, never {@code Instant} arithmetic), so a
     * DST weekend keeps "09:00 local" at 09:00 local on both sides of the shift
     * — the reason a coach's availability is stored as time-of-day at all.
     * Chunks step by {@code durationMinutes} from the window start, so a 45-min
     * session in a 09:00–17:00 window is offered at 09:00, 09:45, 10:30 … and
     * the tail that does not fit is dropped.
     */
    public static List<Instant> slots(List<Rule> rules, List<TimeRange> blocks, List<TimeRange> busy,
                                      ZoneId zone, int durationMinutes,
                                      Instant from, Instant to, Instant now) {
        if (rules.isEmpty() || durationMinutes <= 0 || !from.isBefore(to)) {
            return List.of();
        }
        Duration step = Duration.ofMinutes(durationMinutes);
        Instant earliest = max(from, now.plus(MIN_NOTICE));
        List<Instant> out = new ArrayList<>();

        LocalDate day = from.atZone(zone).toLocalDate();
        LocalDate last = to.atZone(zone).toLocalDate();
        for (; !day.isAfter(last); day = day.plusDays(1)) {
            int weekday = day.getDayOfWeek().getValue();
            for (Rule rule : rules) {
                if (rule.weekday() != weekday) {
                    continue;
                }
                // ZonedDateTime.of resolves a DST gap forward and a DST overlap
                // to the earlier offset — both are the sane reading of "the
                // coach said 09:00 that day".
                Instant windowEnd = ZonedDateTime.of(day, rule.endTime(), zone).toInstant();
                for (Instant start = ZonedDateTime.of(day, rule.startTime(), zone).toInstant();
                     !start.plus(step).isAfter(windowEnd);
                     start = start.plus(step)) {
                    Instant end = start.plus(step);
                    if (start.isBefore(earliest) || !start.isBefore(to)) {
                        continue;
                    }
                    if (overlapsAny(start, end, blocks) || overlapsAny(start, end, busy)) {
                        continue;
                    }
                    out.add(start);
                }
            }
        }
        return out.stream().distinct().sorted().toList();
    }

    private static boolean overlapsAny(Instant start, Instant end, List<TimeRange> ranges) {
        for (TimeRange r : ranges) {
            if (start.isBefore(r.end()) && end.isAfter(r.start())) {
                return true;
            }
        }
        return false;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
