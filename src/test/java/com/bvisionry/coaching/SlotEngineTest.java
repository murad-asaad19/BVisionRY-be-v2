package com.bvisionry.coaching;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bvisionry.coaching.domain.SlotEngine;
import com.bvisionry.coaching.domain.SlotEngine.Rule;
import com.bvisionry.common.calendar.TimeRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slot engine (coaching-sessions spec §5) with no Spring, no database and
 * no wall clock — {@code now} is a parameter precisely so the min-notice rule
 * and the DST case are assertable rather than time-of-day dependent.
 */
class SlotEngineTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** A Wednesday, well clear of any DST boundary. */
    private static final Instant NOW = Instant.parse("2026-06-03T08:00:00Z");

    @Test
    @DisplayName("a window is chunked by duration, aligned to its start, and the tail is dropped")
    void chunksAlignToTheWindowStart() {
        // Thursday 09:00–11:00 UTC, 45 minutes: 09:00, 09:45, 10:30 does not fit
        // (it would end at 11:15), so two slots.
        List<Instant> slots = SlotEngine.slots(
                List.of(new Rule(4, LocalTime.of(9, 0), LocalTime.of(11, 0))),
                List.of(), List.of(), UTC, 45,
                day("2026-06-04T00:00:00Z"), day("2026-06-05T00:00:00Z"), NOW);

        assertThat(slots).containsExactly(
                Instant.parse("2026-06-04T09:00:00Z"),
                Instant.parse("2026-06-04T09:45:00Z"));
    }

    @Test
    @DisplayName("an existing booking removes exactly its own chunk")
    void aBusyRangeRemovesOnlyItsChunk() {
        List<Instant> slots = SlotEngine.slots(
                List.of(new Rule(4, LocalTime.of(9, 0), LocalTime.of(12, 0))),
                List.of(),
                List.of(new TimeRange(Instant.parse("2026-06-04T10:00:00Z"),
                        Instant.parse("2026-06-04T11:00:00Z"))),
                UTC, 60,
                day("2026-06-04T00:00:00Z"), day("2026-06-05T00:00:00Z"), NOW);

        assertThat(slots).containsExactly(
                Instant.parse("2026-06-04T09:00:00Z"),
                Instant.parse("2026-06-04T11:00:00Z"));
    }

    @Test
    @DisplayName("a block removes every chunk it touches, not just the one it starts in")
    void aBlockRemovesEveryOverlappingChunk() {
        // 10:30–11:30 straddles the 10:00 and 11:00 chunks and clears both.
        List<Instant> slots = SlotEngine.slots(
                List.of(new Rule(4, LocalTime.of(9, 0), LocalTime.of(13, 0))),
                List.of(new TimeRange(Instant.parse("2026-06-04T10:30:00Z"),
                        Instant.parse("2026-06-04T11:30:00Z"))),
                List.of(), UTC, 60,
                day("2026-06-04T00:00:00Z"), day("2026-06-05T00:00:00Z"), NOW);

        assertThat(slots).containsExactly(
                Instant.parse("2026-06-04T09:00:00Z"),
                Instant.parse("2026-06-04T12:00:00Z"));
    }

    @Test
    @DisplayName("nothing inside the 12-hour minimum notice is ever offered")
    void minimumNoticeDropsTheNearTermSlots() {
        // now = Wed 08:00Z, so the floor is Wed 20:00Z — the whole of Wednesday's
        // 09:00–17:00 window is inside it, and Thursday's survives untouched.
        List<Instant> slots = SlotEngine.slots(
                List.of(new Rule(3, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        new Rule(4, LocalTime.of(9, 0), LocalTime.of(17, 0))),
                List.of(), List.of(), UTC, 60,
                day("2026-06-03T00:00:00Z"), day("2026-06-05T00:00:00Z"), NOW);

        assertThat(slots).isNotEmpty();
        assertThat(slots).allSatisfy(s ->
                assertThat(s).isAfterOrEqualTo(NOW.plus(SlotEngine.MIN_NOTICE)));
        assertThat(slots.getFirst()).isEqualTo(Instant.parse("2026-06-04T09:00:00Z"));
    }

    @Test
    @DisplayName("a day the coach has no rule for yields nothing")
    void aWeekdayOutsideTheRulesIsEmpty() {
        // Only Mondays (1); the range is Thursday alone.
        assertThat(SlotEngine.slots(
                List.of(new Rule(1, LocalTime.of(9, 0), LocalTime.of(17, 0))),
                List.of(), List.of(), UTC, 60,
                day("2026-06-04T00:00:00Z"), day("2026-06-05T00:00:00Z"), NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("across a DST change every slot still starts at the coach's wall-clock 09:00")
    void dstKeepsTheWallClockStart() {
        // Europe/Berlin springs forward on Sunday 2026-03-29. A daily 09:00–10:00
        // rule across that week must stay 09:00 LOCAL on both sides — which means
        // the UTC instant shifts by an hour, and that is the point.
        List<Rule> everyDay = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(d -> new Rule(d, LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .toList();
        Instant from = Instant.parse("2026-03-26T00:00:00Z");
        Instant to = Instant.parse("2026-04-02T00:00:00Z");

        List<Instant> slots = SlotEngine.slots(everyDay, List.of(), List.of(), BERLIN, 60,
                from, to, Instant.parse("2026-03-20T00:00:00Z"));

        assertThat(slots).isNotEmpty();
        assertThat(slots).allSatisfy(s ->
                assertThat(ZonedDateTime.ofInstant(s, BERLIN).toLocalTime())
                        .isEqualTo(LocalTime.of(9, 0)));
        // Before the shift 09:00 Berlin is 08:00Z; after it, 07:00Z.
        assertThat(slots).contains(Instant.parse("2026-03-27T08:00:00Z"),
                Instant.parse("2026-03-31T07:00:00Z"));
    }

    @Test
    @DisplayName("no rules, no duration or an inverted range is an empty offer, not an error")
    void degenerateInputsAreEmpty() {
        List<Rule> rule = List.of(new Rule(4, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        Instant from = day("2026-06-04T00:00:00Z");
        Instant to = day("2026-06-05T00:00:00Z");

        assertThat(SlotEngine.slots(List.of(), List.of(), List.of(), UTC, 60, from, to, NOW)).isEmpty();
        assertThat(SlotEngine.slots(rule, List.of(), List.of(), UTC, 0, from, to, NOW)).isEmpty();
        assertThat(SlotEngine.slots(rule, List.of(), List.of(), UTC, 60, to, from, NOW)).isEmpty();
    }

    private static Instant day(String iso) {
        return Instant.parse(iso).plus(Duration.ZERO);
    }
}
