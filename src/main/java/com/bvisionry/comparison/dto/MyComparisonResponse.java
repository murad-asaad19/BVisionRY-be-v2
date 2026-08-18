package com.bvisionry.comparison.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The member's growth-comparison read (spec §5 report lifecycle).
 *
 * <p>{@code state}: {@code done} = comparison computed; {@code pending} = the
 * designated pair has not produced a stored comparison yet; {@code none} = no
 * designated pair anywhere — the guard state, no report is teased.
 * {@code comparison} is non-null only when done. The trajectory is always
 * returned so the UI can draw 58 → 64 → locked.
 *
 * <p>{@code pendingReason} splits the two very different flavors of pending,
 * non-null only in that state. {@code awaiting-distance} = a side is genuinely
 * missing, the member still has an assessment to sit. {@code awaiting-compute}
 * = BOTH sides are evaluated and the comparison simply never landed (the
 * compute is swallowed on failure). Telling a founder their distance was "not
 * taken" when the data exists is the lie this field is here to prevent — the
 * copy must key off it, never off {@code state} alone.
 */
public record MyComparisonResponse(
        String state,
        UUID cohortId,
        String cohortName,
        FounderComparisonDto comparison,
        List<TrajectoryPoint> trajectory,
        String pendingReason) {

    public record TrajectoryPoint(
            UUID submissionId,
            String pipelineName,
            BigDecimal overallScore,
            Instant evaluatedAt) {
    }
}
