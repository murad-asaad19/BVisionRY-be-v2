package com.bvisionry.comparison.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One founder's stored distance comparison, pillar rows included. Timestamps
 * (§7b): when each side was evaluated and when the comparison was computed.
 */
public record FounderComparisonDto(
        UUID id,
        UUID cohortId,
        UUID userId,
        String userName,
        BigDecimal overallBefore,
        BigDecimal overallAfter,
        BigDecimal overallDelta,
        String overallBandKey,
        String overallBandLabel,
        Instant baselineEvaluatedAt,
        Instant distanceEvaluatedAt,
        Instant computedAt,
        List<ComparisonPillarDto> pillars) {

    /**
     * A pillar row. {@code state} is MAPPED / NEWLY_MEASURED / NOT_REMEASURED —
     * one-sided rows are always present, never silently omitted (spec §5).
     */
    public record ComparisonPillarDto(
            UUID baselinePillarId,
            UUID distancePillarId,
            String pillarName,
            String state,
            BigDecimal beforePct,
            BigDecimal afterPct,
            BigDecimal delta,
            String bandKey,
            String bandLabel,
            String maturityBefore,
            String maturityAfter) {
    }
}
