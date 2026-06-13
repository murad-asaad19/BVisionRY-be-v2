package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OverallSummaryResult(
        @JsonProperty(value = "overallScorePercentage")
        int overallScorePercentage,

        @JsonProperty(value = "summaryNarrative")
        String summaryNarrative,

        @JsonProperty(value = "strengths")
        List<String> strengths,

        @JsonProperty(value = "developmentAreas")
        List<String> developmentAreas,

        @JsonProperty(value = "recommendations")
        List<String> recommendations,

        @JsonProperty(value = "corePattern")
        String corePattern,

        @JsonProperty(value = "movingForward")
        String movingForward
) {
    public OverallSummaryResult {
        if (summaryNarrative == null) summaryNarrative = "";
        if (strengths == null) strengths = List.of();
        if (developmentAreas == null) developmentAreas = List.of();
        if (recommendations == null) recommendations = List.of();
    }
}
