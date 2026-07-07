package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured output of the AI-use detector: how likely a submission's free-text
 * answers were written by an AI rather than the respondent. The score is a
 * heuristic confidence indicator, never proof — the verdict band is derived
 * server-side from the score so the model can't invent its own labels.
 */
public record AiUseDetectionResult(
        @JsonProperty(value = "aiLikelihoodScore")
        int aiLikelihoodScore,

        @JsonProperty(value = "answerFindings")
        List<AnswerFinding> answerFindings
) {
    public AiUseDetectionResult {
        if (answerFindings == null) answerFindings = List.of();
    }

    /** A per-answer signal the detector flagged, citing the question by qid. */
    public record AnswerFinding(
            @JsonProperty("qid") String qid,
            @JsonProperty("note") String note
    ) {
        public AnswerFinding {
            if (qid == null) qid = "";
            if (note == null) note = "";
        }
    }
}
