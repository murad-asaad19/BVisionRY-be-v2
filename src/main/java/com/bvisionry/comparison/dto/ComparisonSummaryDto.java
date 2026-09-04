package com.bvisionry.comparison.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the org-admin cohort comparison list — the "Growth by member"
 * table. Besides the overall numbers it carries the two things that table
 * shows per founder without opening them: where their narratives stand in
 * review, and the one pillar that moved most. Counts and a name only; no
 * narrative prose rides on a list read (§6).
 */
public record ComparisonSummaryDto(
        UUID userId,
        String userName,
        BigDecimal overallBefore,
        BigDecimal overallAfter,
        BigDecimal overallDelta,
        String overallBandKey,
        String overallBandLabel,
        Instant computedAt,
        /**
         * Distance pillars measured on BOTH sides — the only ones a narrative
         * can still be attached to, so the denominator of "11 of 11 approved".
         */
        int mappedPillarCount,
        /** APPROVED, still-attached narratives. */
        int approvedNarrativeCount,
        /** Still awaiting review — the org admin's review queue, per founder. */
        int draftNarrativeCount,
        /** The mapped pillar that rose most; both null when none rose. */
        String topGainPillarName,
        BigDecimal topGainDelta,
        /** The mapped pillar that fell most; both null when none fell. */
        String topDropPillarName,
        BigDecimal topDropDelta) {
}
