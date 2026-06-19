package com.bvisionry.aiengine.confidence;

import com.bvisionry.common.dto.PillarEvaluationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure tests for the model-agnostic confidence heuristic and sample aggregation.
 */
class ConfidenceGateTest {

    private final ConfidenceGate gate = new ConfidenceGate();

    private static final Map<String, List<Integer>> THRESHOLDS = Map.of(
            "Emerging", List.of(0, 59),
            "Strong", List.of(60, 79),
            "Elite", List.of(80, 100));

    @Test
    void midBandScore_isConfident() {
        assertThat(gate.isBorderline(new BigDecimal("70"), THRESHOLDS, 3)).isFalse();
    }

    @Test
    void scoreNearAnInteriorBoundary_isBorderline() {
        assertThat(gate.isBorderline(new BigDecimal("61"), THRESHOLDS, 3)).isTrue();  // near 59/60
        assertThat(gate.isBorderline(new BigDecimal("78"), THRESHOLDS, 3)).isTrue();  // near 79/80
    }

    @Test
    void zeroAndHundredExtremes_areNotBorderline() {
        assertThat(gate.isBorderline(new BigDecimal("0"), THRESHOLDS, 3)).isFalse();
        assertThat(gate.isBorderline(new BigDecimal("100"), THRESHOLDS, 3)).isFalse();
    }

    @Test
    void median_handlesOddAndEvenCounts() {
        assertThat(gate.median(List.of(70, 60, 80))).isEqualTo(70);
        assertThat(gate.median(List.of(60, 80))).isEqualTo(70);
    }

    @Test
    void closestToScore_picksNearestSample() {
        PillarEvaluationResult a = pillar(60);
        PillarEvaluationResult b = pillar(72);
        PillarEvaluationResult c = pillar(80);
        assertThat(gate.closestToScore(List.of(a, b, c), 70).scorePercentage()).isEqualTo(72);
    }

    private static PillarEvaluationResult pillar(int score) {
        return new PillarEvaluationResult(score, "", List.of(), List.of(), "", List.of());
    }
}
