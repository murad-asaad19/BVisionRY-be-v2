package com.bvisionry.evaluation.dto;

import java.time.Instant;
import java.util.List;

/**
 * Admin-facing AI-use detection result for one submission. The verdict band is
 * derived from the score server-side (never model-invented) so the UI and any
 * future consumers band identically.
 */
public record AiDetectionResponse(
        int aiLikelihoodScore,
        String verdict,
        List<Finding> findings,
        String model,
        Instant detectedAt
) {
    /** A per-answer signal, with the question text resolved at detection time. */
    public record Finding(String questionId, String questionText, String note) {}

    public static String verdictFor(int score) {
        if (score >= 75) return "LIKELY_AI";
        if (score >= 50) return "POSSIBLY_AI";
        if (score >= 25) return "UNCERTAIN";
        return "LIKELY_HUMAN";
    }
}
