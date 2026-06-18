package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.SubscriptionTier;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SimulateRequest(
        @NotNull(message = "Answers map is required")
        Map<String, AnswerInput> answers,

        @NotNull(message = "Tier is required")
        SubscriptionTier tier,

        /**
         * When true, simulate the public (QR-link) assessment flow: evaluation uses
         * the {@code PUBLIC_ASSESSMENT_SYSTEM_PROMPT} and the configured public
         * assessment model. The real public flow always runs at PREMIUM gating
         * (see {@code EvaluationService} — tier is PREMIUM when there is no
         * assignment), so callers send {@code tier=PREMIUM} alongside this flag.
         * Defaults to false (a normal member free/premium simulation).
         */
        boolean publicAssessment
) {
    public record AnswerInput(
            String responseText,
            String selectedValue
    ) {}
}
