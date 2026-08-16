package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The cohort-level growth report's model output (spec §4) — the staff-facing
 * sibling of {@link TeamInsightResult}, deliberately much smaller: one overview
 * plus three lists is everything the cohort Growth tab renders, and every extra
 * key is another thing to keep in sync between the prompt, the guardrail and
 * the web contract.
 */
public record CohortGrowthSummaryResult(
        @JsonProperty(required = true, value = "overview")
        String overview,

        @JsonProperty(required = true, value = "sharedWins")
        List<String> sharedWins,

        @JsonProperty(required = true, value = "sharedRisks")
        List<String> sharedRisks,

        @JsonProperty(required = true, value = "recommendations")
        List<String> recommendations
) {}
