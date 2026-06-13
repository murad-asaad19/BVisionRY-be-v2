package com.bvisionry.aiconfig.dto;

import com.bvisionry.common.dto.PillarEvaluationResult;

import java.math.BigDecimal;

/**
 * {@code scorePercentage} and {@code maturityLabel} are derived from the pillar's
 * admin-defined thresholds — the AI never returns a maturity label.
 */
public record TryItOutResponse(
        PillarEvaluationResult evaluation,
        BigDecimal scorePercentage,
        String maturityLabel,
        String modelUsed,
        long latencyMs,
        String rawResponse
) {}
