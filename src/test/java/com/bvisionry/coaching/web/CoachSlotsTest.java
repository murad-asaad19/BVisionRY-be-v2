package com.bvisionry.coaching.web;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bvisionry.common.calendar.TimeRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one piece of {@link CoachSlots} that is arithmetic rather than wiring:
 * taking the session being rescheduled back OUT of the coach's real calendar.
 * Its own Google event is still sitting there, so without this a session can
 * only ever be moved by MORE than its own duration (sessions spec §6.3).
 */
class CoachSlotsTest {

    private static final Instant NINE = Instant.parse("2026-06-04T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-06-04T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-06-04T11:00:00Z");
    private static final Instant TWELVE = Instant.parse("2026-06-04T12:00:00Z");

    @Test
    @DisplayName("nothing to cut leaves the busy range exactly as it was")
    void noCutIsIdentity() {
        TimeRange range = new TimeRange(NINE, ELEVEN);
        assertThat(CoachSlots.minus(range, null)).containsExactly(range);
        assertThat(CoachSlots.minus(range, new TimeRange(ELEVEN, TWELVE))).containsExactly(range);
        // Half-open: touching at the boundary is not an overlap.
        assertThat(CoachSlots.minus(range, new TimeRange(TWELVE, TWELVE.plusSeconds(1))))
                .containsExactly(range);
    }

    @Test
    @DisplayName("a cut covering the whole range removes it")
    void aCoveringCutRemovesTheRange() {
        assertThat(CoachSlots.minus(new TimeRange(TEN, ELEVEN), new TimeRange(NINE, TWELVE)))
                .isEmpty();
        assertThat(CoachSlots.minus(new TimeRange(TEN, ELEVEN), new TimeRange(TEN, ELEVEN)))
                .isEmpty();
    }

    @Test
    @DisplayName("a cut inside the range leaves the two pieces around it")
    void anInnerCutSplitsTheRange() {
        assertThat(CoachSlots.minus(new TimeRange(NINE, TWELVE), new TimeRange(TEN, ELEVEN)))
                .containsExactly(new TimeRange(NINE, TEN), new TimeRange(ELEVEN, TWELVE));
    }

    @Test
    @DisplayName("a cut overlapping one end shortens the range from that end only")
    void anOverlappingCutTrimsOneEnd() {
        assertThat(CoachSlots.minus(new TimeRange(TEN, TWELVE), new TimeRange(NINE, ELEVEN)))
                .containsExactly(new TimeRange(ELEVEN, TWELVE));
        assertThat(CoachSlots.minus(new TimeRange(NINE, ELEVEN), new TimeRange(TEN, TWELVE)))
                .containsExactly(new TimeRange(NINE, TEN));
    }

    @Test
    @DisplayName("the result never widens the range it came from")
    void theResultNeverWidens() {
        List<TimeRange> pieces =
                CoachSlots.minus(new TimeRange(NINE, TWELVE), new TimeRange(TEN, ELEVEN));
        assertThat(pieces).allSatisfy(p -> {
            assertThat(p.start()).isAfterOrEqualTo(NINE);
            assertThat(p.end()).isBeforeOrEqualTo(TWELVE);
            assertThat(p.start()).isBefore(p.end());
        });
    }
}
