package com.bvisionry.comparison.dto;

import com.bvisionry.comparison.domain.NarrativeKind;

import java.math.BigDecimal;
import java.time.Instant;
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
     * One distance pillar across the cohort. {@code kindCounts} holds every
     * {@link com.bvisionry.comparison.domain.NarrativeKind}, zero-filled: the count of DISTINCT members whose APPROVED,
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
            List<BandMoveDto> bandMoves,
            /**
             * Members who ended this pillar in a HIGHER band than they started —
             * the sum of the non-held, non-down {@link BandMoveDto} buckets,
             * pre-summed because it is the table's ranking column. Its
             * denominator is the banded population (the sum over
             * {@code bandMoves}), never {@code measuredCount}.
             */
            int movedUpCount,
            /**
             * The banded population split by band, before and after — the
             * Growth tab's paired stacked bars. In the pillar's ORDINAL band
             * order (its thresholds sorted by minimum score), so the bar reads
             * left-to-right from the lowest band; a label the current thresholds
             * no longer name (a rename since the reading, or "Unknown") is
             * appended after them so every banded member is still drawn. Each
             * side sums to the same population as {@code bandMoves}.
             */
            List<BandCountDto> bands,
            /**
             * The programme content tagged to this pillar: every LIVE task of
             * the cohort carrying the tag, with the org slice's done / reached.
             * Empty means "not yet built" — nothing in this cohort's curriculum
             * grows the pillar, so whatever moved here came from the assessment
             * alone. That is the category the Growth tab sorts it into, and
             * computing it here is what stops it depending on anyone's memory.
             */
            List<PillarContentDto> content,
            /** The coach's note for this org's slice (V214); null when none. */
            String coachNote,
            Instant coachNoteUpdatedAt) {
    }

    /**
     * One band's head-count on each side; {@code label} is the pillar's own
     * vocabulary. {@code onAxis} is false for a label the pillar's CURRENT
     * thresholds no longer name ("Unknown" from a failed evaluation, or a
     * rename since the reading): it is appended after the ordered bands so
     * every banded member is still drawn, but it is never the top band and a
     * reader must not tone it as one.
     */
    public record BandCountDto(String label, int before, int after, boolean onAxis) {
    }

    /**
     * One tagged task's reach in the org slice. {@code memberCount} is the
     * members the task's module actually reaches ({@code ProgramAudience}), and
     * {@code doneCount} how many of them have done it by the one completion rule
     * every surface shares ({@code TaskCompletion}).
     */
    public record PillarContentDto(UUID taskId, String name, String taskType,
                                   int doneCount, int memberCount) {
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
    public record BandMoveDto(String from, String to, int members, boolean held,
                              /**
                               * A move to a LOWER band. Decided by the pillar's
                               * band order when both labels are on it; a label
                               * the current thresholds no longer name falls back
                               * to the sign of the members' own shift, which
                               * bands are monotonic in. False on held buckets.
                               */
                              boolean down) {
    }
}
