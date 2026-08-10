package com.bvisionry.founderprofile.web;

import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.FriPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The header's Δ-so-far math (pure): latest minus earliest, null under two points. */
class FounderProfileServiceTest {

    private static FriPoint point(String score) {
        return new FriPoint(score == null ? null : new BigDecimal(score), Instant.now());
    }

    @Test
    void latestMinusEarliest() {
        assertThat(FounderProfileService.friDelta(
                List.of(point("50.00"), point("64.00"), point("71.25"))))
                .isEqualByComparingTo("21.25");
    }

    @Test
    void declineIsNegative() {
        assertThat(FounderProfileService.friDelta(List.of(point("60.00"), point("52.50"))))
                .isEqualByComparingTo("-7.50");
    }

    @Test
    void underTwoPointsIsNull() {
        assertThat(FounderProfileService.friDelta(List.of())).isNull();
        assertThat(FounderProfileService.friDelta(List.of(point("58.00")))).isNull();
    }

    @Test
    void nullScoresYieldNullNotAnException() {
        assertThat(FounderProfileService.friDelta(List.of(point(null), point("58.00")))).isNull();
    }
}
