package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PillarEvaluationResult(
        @JsonProperty(value = "scorePercentage")
        int scorePercentage,

        @JsonProperty(value = "whatThisScoreMeans")
        String whatThisScoreMeans,

        @JsonProperty(value = "whatsWorking")
        List<String> whatsWorking,

        @JsonProperty(value = "whatCanImprove")
        List<String> whatCanImprove,

        @JsonProperty(value = "whyThisMattersForBusiness")
        String whyThisMattersForBusiness,

        @JsonProperty(value = "evidence")
        List<Evidence> evidence
) {
    public PillarEvaluationResult {
        if (whatsWorking == null) whatsWorking = List.of();
        if (whatCanImprove == null) whatCanImprove = List.of();
        if (evidence == null) evidence = List.of();
        if (whatThisScoreMeans == null) whatThisScoreMeans = "";
        if (whyThisMattersForBusiness == null) whyThisMattersForBusiness = "";
    }

    public record Evidence(
            @JsonProperty("qid") String qid,
            @JsonProperty("quote") String quote
    ) {
        public Evidence {
            if (qid == null) qid = "";
            if (quote == null) quote = "";
        }
    }
}
