package com.bvisionry.aiconfig.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record TryItOutRequest(
        @NotNull(message = "Pillar ID is required")
        UUID pillarId,

        @NotNull(message = "Answers are required")
        Map<String, AnswerInput> answers,

        String model
) {
    public record AnswerInput(String responseText, String selectedValue) {}
}
