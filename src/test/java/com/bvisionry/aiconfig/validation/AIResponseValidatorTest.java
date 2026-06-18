package com.bvisionry.aiconfig.validation;

import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIResponseValidatorTest {

    private final AIResponseValidator validator = new AIResponseValidator();

    @Test
    void validatePillarResult_validResult_passes() {
        PillarEvaluationResult result = new PillarEvaluationResult(
                75, "Good progress in this area.",
                List.of("Clear strategy"), List.of("Need more data"),
                "Drives competitive advantage",
                List.of()
        );

        PillarEvaluationResult validated = validator.validatePillarResult(result);
        assertThat(validated.scorePercentage()).isEqualTo(75);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, -50, 200})
    void validatePillarResult_invalidScore_throws(int invalidScore) {
        PillarEvaluationResult result = new PillarEvaluationResult(
                invalidScore, "Explanation",
                List.of("Working"), List.of("Improve"), "Business",
                List.of()
        );

        assertThatThrownBy(() -> validator.validatePillarResult(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Score must be between 0 and 100");
    }

    @Test
    void validatePillarResult_boundaryScores_pass() {
        PillarEvaluationResult zero = new PillarEvaluationResult(
                0, "Starting out",
                List.of(), List.of("Everything"), "Foundation",
                List.of()
        );
        assertThat(validator.validatePillarResult(zero).scorePercentage()).isEqualTo(0);

        PillarEvaluationResult hundred = new PillarEvaluationResult(
                100, "Perfect",
                List.of("Everything"), List.of(), "Leadership",
                List.of()
        );
        assertThat(validator.validatePillarResult(hundred).scorePercentage()).isEqualTo(100);
    }

    @Test
    void validatePillarResult_longText_passesThrough() {
        // Length is controlled via the prompt, not the validator.
        String longText = "A".repeat(3000);
        PillarEvaluationResult result = new PillarEvaluationResult(
                50, longText,
                List.of("Working"), List.of("B".repeat(3000)), longText,
                List.of()
        );

        PillarEvaluationResult validated = validator.validatePillarResult(result);
        assertThat(validated.whatThisScoreMeans()).hasSize(3000);
        assertThat(validated.whyThisMattersForBusiness()).hasSize(3000);
        assertThat(validated.whatCanImprove().get(0)).hasSize(3000);
    }

    @Test
    void validatePillarResult_stripsInlineQidReferences() {
        PillarEvaluationResult result = new PillarEvaluationResult(
                60, "Strong area (qid: 12345678-1234-1234-1234-123456789abc)",
                List.of("Working Q: deadbeef12345678"),
                List.of("Improve"), "Business",
                List.of()
        );

        PillarEvaluationResult validated = validator.validatePillarResult(result);
        assertThat(validated.whatThisScoreMeans()).isEqualTo("Strong area");
        assertThat(validated.whatsWorking().get(0)).isEqualTo("Working");
    }

    @Test
    void validateOverallSummaryResult_validResult_passes() {
        OverallSummaryResult result = new OverallSummaryResult(
                82, "Strong performance across all pillars.",
                List.of("Leadership"), List.of("Communication"),
                null, null
        );

        OverallSummaryResult validated = validator.validateOverallSummaryResult(result);
        assertThat(validated.overallScorePercentage()).isEqualTo(82);
    }

    @Test
    void validateOverallSummaryResult_invalidScore_throws() {
        OverallSummaryResult result = new OverallSummaryResult(
                150, "Summary", List.of(), List.of(),
                null, null
        );

        assertThatThrownBy(() -> validator.validateOverallSummaryResult(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Score must be between 0 and 100");
    }
}
