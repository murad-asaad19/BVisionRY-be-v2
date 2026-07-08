package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TeamInsightResult(
        @JsonProperty(required = true, value = "teamThemes")
        TeamThemes teamThemes,

        @JsonProperty(required = true, value = "individualCoaching")
        List<IndividualCoaching> individualCoaching,

        @JsonProperty(required = true, value = "benchmarking")
        Benchmarking benchmarking
) {

    public record TeamThemes(
            @JsonProperty(required = true, value = "commonStrengths")
            List<String> commonStrengths,

            @JsonProperty(required = true, value = "growthEdges")
            List<String> growthEdges,

            @JsonProperty(required = true, value = "patterns")
            List<String> patterns,

            @JsonProperty(required = true, value = "recommendations")
            List<String> recommendations
    ) {}

    public record IndividualCoaching(
            @JsonProperty(required = true, value = "memberId")
            String memberId,

            @JsonProperty(required = true, value = "focusAreas")
            List<String> focusAreas,

            @JsonProperty(required = true, value = "suggestedActions")
            List<String> suggestedActions
    ) {}

    public record Benchmarking(
            @JsonProperty(required = true, value = "teamVsPlatformComparison")
            String teamVsPlatformComparison,

            @JsonProperty(required = true, value = "outlierPillars")
            List<String> outlierPillars
    ) {}
}
