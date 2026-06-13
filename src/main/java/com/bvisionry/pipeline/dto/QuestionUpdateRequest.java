package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.QuestionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

public record QuestionUpdateRequest(
        @NotNull(message = "Question type is required")
        QuestionType type,

        @NotBlank(message = "Prompt text is required")
        String promptText,

        Boolean isRequired,

        @DecimalMin(value = "0.01", message = "Weight must be greater than 0")
        BigDecimal weight,

        Map<String, Object> configJson
) {}
