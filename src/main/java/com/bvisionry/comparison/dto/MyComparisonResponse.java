package com.bvisionry.comparison.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The member's growth-comparison read (spec §5 report lifecycle).
 *
 * <p>{@code state}: {@code done} = comparison computed; {@code pending} = the
 * member's cohort designates a distance assessment that is not evaluated yet;
 * {@code none} = no designated pair anywhere — the guard state, no report is
 * teased. {@code comparison} is non-null only when done. The trajectory is
 * always returned so the UI can draw 58 → 64 → locked.
 */
public record MyComparisonResponse(
        String state,
        UUID cohortId,
        String cohortName,
        FounderComparisonDto comparison,
        List<TrajectoryPoint> trajectory) {

    public record TrajectoryPoint(
            UUID submissionId,
            String pipelineName,
            BigDecimal overallScore,
            Instant evaluatedAt) {
    }
}
