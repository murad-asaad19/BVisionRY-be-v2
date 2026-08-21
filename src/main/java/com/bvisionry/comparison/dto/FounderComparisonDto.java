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
        List<ComparisonPillarDto> pillars,
        /**
         * APPROVED shift narratives only (spec §6) — this DTO is the member's
         * report payload as well as the staff one, so the approved-only filter
         * lives here, once, where a draft cannot leak past it. Staff review
         * drafts through the separate narrative-review endpoints.
         */
        List<ShiftNarrativeDto> narratives,
        /**
         * How many narratives are still awaiting review — a COUNT and nothing
         * else, so My Growth can say "2 more insights are being reviewed" (spec
         * §6 / the prototype's dashed placeholder) without a single unapproved
         * word crossing the wire.
         */
        int draftNarrativeCount,
        /**
         * The member-level growth summary (spec §3) — the APPROVED content or
         * null. Same one-way gate as {@code narratives}: this DTO is the
         * member's payload, so a draft cannot reach it. Staff read the draft
         * through the separate {@code /summary} review endpoints.
         *
         * <p>Legacy paragraph only: everything generated since V193 arrives in
         * {@code growthSummaryItems}. Exactly one of the two is populated.
         */
        String growthSummary,
        /**
         * The APPROVED overall breakdown (V193) — the same kinds the
         * per-pillar narratives carry, merged across pillars. Null on a
         * pre-V193 summary (read {@code growthSummary}).
         */
        List<ShiftNarrativeDto.NarrativeItemDto> growthSummaryItems) {

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
