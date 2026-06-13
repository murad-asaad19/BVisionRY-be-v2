package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.SubscriptionTier;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SimulateRequest(
        @NotNull(message = "Answers map is required")
        Map<String, AnswerInput> answers,

        @NotNull(message = "Tier is required")
        SubscriptionTier tier
) {
    public record AnswerInput(
            String responseText,
            String selectedValue
    ) {}
}
