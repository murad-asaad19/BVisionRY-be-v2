package com.bvisionry.aiconfig.dto;

import com.bvisionry.common.enums.AIProvider;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AIConfigUpdateRequest(
        @NotNull(message = "Provider is required")
        AIProvider provider,

        @NotBlank(message = "Default evaluation model is required")
        String defaultEvaluationModel,

        // Null/blank = public assessments inherit the default evaluation model.
        String publicAssessmentModel,

        @NotBlank(message = "Default insight model is required")
        String defaultInsightModel,

        // @NotNull guards the GLOBAL singleton config: the UI serializes a cleared
        // numeric input as null, and @DecimalMin/@DecimalMax are skipped for null,
        // so a null would otherwise persist and degrade AI for every org.
        @NotNull(message = "Evaluation temperature is required")
        @DecimalMin(value = "0.0", message = "Temperature must be >= 0.0")
        @DecimalMax(value = "2.0", message = "Temperature must be <= 2.0")
        BigDecimal evaluationTemperature,

        @NotNull(message = "Insight temperature is required")
        @DecimalMin(value = "0.0", message = "Temperature must be >= 0.0")
        @DecimalMax(value = "2.0", message = "Temperature must be <= 2.0")
        BigDecimal insightTemperature,

        @Min(value = 100, message = "Max tokens must be >= 100")
        int maxTokensEvaluation,

        @Min(value = 100, message = "Max tokens must be >= 100")
        int maxTokensInsight
) {}
