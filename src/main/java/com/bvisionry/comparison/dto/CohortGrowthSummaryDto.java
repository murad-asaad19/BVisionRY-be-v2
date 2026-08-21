package com.bvisionry.comparison.dto;

import com.bvisionry.common.enums.InsightReportStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One cohort-level growth report as the staff Growth tab reads it (spec §4).
 *
 * <p>{@code report} is the model's own object — {@code overview},
 * {@code sharedWins}, {@code sharedRisks}, {@code recommendations} — carried as
 * a map for the same reason {@code InsightReportResponse.reportJson} is: the
 * shape is the PROMPT's contract, and re-declaring it here would mean two
 * places to edit whenever the template's output section moves. Null while
 * GENERATING and after a FAILED run.
 */
public record CohortGrowthSummaryDto(
        UUID id,
        UUID cohortId,
        InsightReportStatus status,
        Map<String, Object> report,
        String failureReason,
        boolean includeNames,
        int membersTotal,
        int membersWithNarratives,
        String aiModelUsed,
        Instant generatedAt) {
}
