package com.bvisionry.comparison.dto;

import com.bvisionry.comparison.domain.NarrativeKind;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cohort's growth picture folded to per-pillar averages — the one payload
 * behind the cohort Growth tab's pillar table, its charts and the growth
 * report export, so no two of those surfaces can quote different numbers.
 *
 * <p>Averages are means over the members who HAVE the value (a one-sided
 * pillar contributes nothing to the sides it lacks), matching the
 * null-not-zero rule the participation average follows.
 */
public record CohortGrowthAggregateDto(
        int membersMeasured,
        BigDecimal avgOverallBefore,
        BigDecimal avgOverallAfter,
        BigDecimal avgOverallDelta,
        List<PillarAggregateDto> pillars) {

    /**
     * One distance pillar across the cohort. {@code kindCounts} holds all five
     * kinds, zero-filled: the count of DISTINCT members whose APPROVED,
     * still-attached narrative carries at least one observation of that kind —
     * "resolved for 3 of 7", where 7 is {@code membersWithApprovedNarrative}.
     * Draft prose never reaches this payload, same rule as every other
     * member-visible read.
     */
    public record PillarAggregateDto(
            UUID distancePillarId,
            String pillarName,
            int measuredCount,
            BigDecimal avgBefore,
            BigDecimal avgAfter,
            BigDecimal avgDelta,
            int membersWithApprovedNarrative,
            Map<NarrativeKind, Integer> kindCounts) {
    }
}
