package com.bvisionry.evaluation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lightweight projection of one pillar evaluation for aggregation views
 * (dashboards). {@code PillarEvaluation} rows carry large always-loaded AI
 * payload columns ({@code ai_raw_response}, rubric snapshots, evidence jsonb)
 * that score aggregations never read — selecting this view instead keeps a
 * large-org dashboard query from hydrating megabytes of unused text.
 */
public record PillarScoreView(
        UUID submissionId,
        UUID pillarId,
        String pillarName,
        String pillarIconKey,
        BigDecimal scorePercentage,
        String maturityLabel) {
}
