package com.bvisionry.aiconfig.dto;

import com.bvisionry.common.enums.AIProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AIConfigResponse(
        UUID id,
        AIProvider provider,
        boolean openRouterKeyConfigured,
        String openRouterKeyMasked,
        boolean anthropicKeyConfigured,
        String anthropicKeyMasked,
        String defaultEvaluationModel,
        String publicAssessmentModel,
        String defaultInsightModel,
        BigDecimal evaluationTemperature,
        BigDecimal insightTemperature,
        int maxTokensEvaluation,
        int maxTokensInsight,
        Instant updatedAt
) {}
