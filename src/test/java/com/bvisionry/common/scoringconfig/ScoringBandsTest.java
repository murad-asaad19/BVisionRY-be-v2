package com.bvisionry.common.scoringconfig;

import com.bvisionry.common.scoringconfig.ScoringBands.Band;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringBandsTest {

    /* ------------------------------------------------------------ resolve */

    @Test
    void defaultShiftBands_resolveAcrossEveryBoundary() {
        List<Band> bands = ScoringBands.defaultShiftBands();
        assertThat(ScoringBands.resolve(bands, new BigDecimal("-12.5")).key()).isEqualTo("decline");
        assertThat(ScoringBands.resolve(bands, new BigDecimal("-0.01")).key()).isEqualTo("decline");
        assertThat(ScoringBands.resolve(bands, BigDecimal.ZERO).key()).isEqualTo("band_1");
        assertThat(ScoringBands.resolve(bands, new BigDecimal("4")).key()).isEqualTo("band_1");
        // Fractional deltas between integer cut-offs still land in a band.
        assertThat(ScoringBands.resolve(bands, new BigDecimal("4.5")).key()).isEqualTo("band_1");
        assertThat(ScoringBands.resolve(bands, new BigDecimal("5")).key()).isEqualTo("band_2");
        assertThat(ScoringBands.resolve(bands, new BigDecimal("14.99")).key()).isEqualTo("band_2");
        assertThat(ScoringBands.resolve(bands, new BigDecimal("15")).key()).isEqualTo("band_3");
        assertThat(ScoringBands.resolve(bands, new BigDecimal("200")).key()).isEqualTo("band_3");
    }

    @Test
    void resolve_emptyList_returnsNull() {
        assertThat(ScoringBands.resolve(List.of(), BigDecimal.ONE)).isNull();
    }

    /* -------------------------------------------------------- shift bands */

    @Test
    void defaultShiftBands_validate() {
        assertThat(ScoringBands.validateShiftBands(ScoringBands.defaultShiftBands())).isEmpty();
    }

    @Test
    void shiftBands_gapBetweenBands_isRejected() {
        Map<String, String> errors = ScoringBands.validateShiftBands(List.of(
                new Band("decline", "Decline", null, -1),
                new Band("band_1", "Low", 0, 4),
                new Band("band_2", "High", 6, null))); // gap: 5 unclaimed
        assertThat(errors).containsKey("bands");
    }

    @Test
    void shiftBands_missingOpenLowEnd_isRejected() {
        Map<String, String> errors = ScoringBands.validateShiftBands(List.of(
                new Band("band_1", "Low", 0, 4),
                new Band("band_2", "High", 5, null)));
        assertThat(errors).containsKey("bands");
    }

    @Test
    void shiftBands_duplicateKeyAndBlankLabel_reportPerFieldErrors() {
        Map<String, String> errors = ScoringBands.validateShiftBands(List.of(
                new Band("decline", "Decline", null, -1),
                new Band("decline", " ", 0, null)));
        assertThat(errors).containsKeys("bands[1].key", "bands[1].label");
    }

    @Test
    void shiftBands_emptyList_isRejected() {
        assertThat(ScoringBands.validateShiftBands(List.of())).containsKey("bands");
    }

    /* ------------------------------------------------------ percent bands */

    @Test
    void defaultParticipationShapedBands_validate() {
        assertThat(ScoringBands.validatePercentBands(List.of(
                new Band("band_1", "High", 80, 100),
                new Band("band_2", "Partial", 50, 79),
                new Band("band_3", "Low", 0, 49)))).isEmpty();
    }

    @Test
    void percentBands_notCoveringZeroTo100_isRejected() {
        assertThat(ScoringBands.validatePercentBands(List.of(
                new Band("band_1", "High", 80, 100),
                new Band("band_2", "Low", 10, 79)))).containsKey("bands");
        assertThat(ScoringBands.validatePercentBands(List.of(
                new Band("band_1", "High", 80, 90),
                new Band("band_2", "Low", 0, 79)))).containsKey("bands");
    }

    @Test
    void percentBands_openEnds_areRejected() {
        assertThat(ScoringBands.validatePercentBands(List.of(
                new Band("band_1", "All", null, 100))))
                .containsKey("bands[0].min");
    }

    @Test
    void percentBands_singleFullRangeBand_isValid() {
        assertThat(ScoringBands.validatePercentBands(List.of(
                new Band("band_1", "All", 0, 100)))).isEmpty();
    }
}
