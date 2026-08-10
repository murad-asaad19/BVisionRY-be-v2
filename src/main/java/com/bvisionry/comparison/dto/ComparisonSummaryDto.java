package com.bvisionry.comparison.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of the org-admin cohort comparison list. */
public record ComparisonSummaryDto(
        UUID userId,
        String userName,
        BigDecimal overallBefore,
        BigDecimal overallAfter,
        BigDecimal overallDelta,
        String overallBandKey,
        String overallBandLabel,
        Instant computedAt) {
}
