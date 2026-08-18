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
            Map<NarrativeKind, Integer> kindCounts,
            List<BandMoveDto> bandMoves) {
    }

    /**
     * How many members crossed from one maturity band to another IN THIS
     * PILLAR — "4 of 7 moved Formative → Strong in Vision Mindset", the thing
     * an org admin can report upward.
     *
     * <p>Deliberately per-pillar and label-based rather than platform-wide:
     * maturity bands are per-pillar free text
     * ({@code pillars.maturity_thresholds_json}), so "Strong" can span a
     * different score range on the next pillar and the labels are NOT
     * comparable across pillars. Grouped inside one pillar they are consistent,
     * because every row here was banded against that pillar's own thresholds.
     * Never sum these across pillars into a single "N founders moved" figure —
     * that number would be meaningless.
     *
     * <p>Read from the frozen {@code maturity_before}/{@code maturity_after}
     * snapshots taken at compute time, so a later threshold edit cannot rewrite
     * history. {@code held} is the no-move case ({@code from} equals
     * {@code to}), kept rather than filtered so the moves and the stayers
     * reconcile against EACH OTHER — the sum of {@code members} over this list
     * is the banded population, and every bucket in it (there is one held
     * bucket per band anyone stayed in, not one in total) has to be rendered
     * for that sum to hold on screen.
     *
     * <p>That sum is NOT {@code measuredCount}, and must not be presented as
     * it. This list is filtered on BOTH maturity snapshots while
     * {@code measuredCount} and the averages are filtered on both percentages,
     * so a row whose pillar produced no band label (an empty
     * {@code maturity_thresholds_json}, or a comparison computed before V161)
     * counts toward {@code measuredCount} and toward no band move at all.
     */
    public record BandMoveDto(String from, String to, int members, boolean held) {
    }
}
