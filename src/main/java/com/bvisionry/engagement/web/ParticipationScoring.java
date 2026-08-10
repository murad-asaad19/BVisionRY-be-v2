package com.bvisionry.engagement.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.bvisionry.common.scoringconfig.ParticipationFormula;
import com.bvisionry.common.scoringconfig.ScoringBands;
import com.bvisionry.engagement.dto.EngagementRecordResponse.CategoryScore;
import com.bvisionry.engagement.dto.EngagementRecordResponse.ParticipationDto;

/**
 * The participation formula (spec §4), pure math. Weighted average of the
 * per-category percentages, with weights RENORMALIZED over the categories
 * that have a denominator: a cohort that held zero coaching sessions must not
 * cost every founder that category's weight. When NO category has a
 * denominator the score is null (no band) — "no data yet", not zero.
 */
final class ParticipationScoring {

    private static final int SCALE = 1;

    private ParticipationScoring() {
    }

    /** One category's config + counts: attended/held or submitted/assigned. */
    record CategoryInput(ParticipationFormula.Category category, int done, int total) {
    }

    static ParticipationDto score(List<CategoryInput> inputs, List<ScoringBands.Band> bands,
                                  Instant computedAt) {
        int weightSum = inputs.stream()
                .filter(in -> in.total() > 0)
                .mapToInt(in -> in.category().weight())
                .sum();

        BigDecimal score = null;
        if (weightSum > 0) {
            BigDecimal weighted = BigDecimal.ZERO;
            for (CategoryInput in : inputs) {
                if (in.total() > 0) {
                    weighted = weighted.add(exactPct(in).multiply(
                            BigDecimal.valueOf(in.category().weight())));
                }
            }
            score = weighted.divide(BigDecimal.valueOf(weightSum), SCALE, RoundingMode.HALF_UP);
        }

        List<CategoryScore> rows = new ArrayList<>();
        for (CategoryInput in : inputs) {
            // weightSum guard: a valid config may hold weight-0 categories; if
            // ONLY those have denominators the sum is 0 — no division, no score.
            boolean included = in.total() > 0 && weightSum > 0;
            BigDecimal pct = included ? exactPct(in).setScale(SCALE, RoundingMode.HALF_UP) : null;
            BigDecimal effectiveWeight = included
                    ? BigDecimal.valueOf(in.category().weight() * 100L)
                            .divide(BigDecimal.valueOf(weightSum), SCALE, RoundingMode.HALF_UP)
                    : null;
            BigDecimal points = included
                    ? exactPct(in).multiply(BigDecimal.valueOf(in.category().weight()))
                            .divide(BigDecimal.valueOf(weightSum), SCALE, RoundingMode.HALF_UP)
                    : null;
            rows.add(new CategoryScore(in.category().key(), in.category().label(),
                    in.category().weight(), in.category().computed(),
                    in.done(), in.total(), pct, effectiveWeight, points));
        }

        ScoringBands.Band band = score == null ? null : ScoringBands.resolve(bands, score);
        return new ParticipationDto(score,
                band == null ? null : band.key(),
                band == null ? null : band.label(),
                rows, computedAt);
    }

    /** done/total as a percentage at full precision — rounded only at the edges. */
    private static BigDecimal exactPct(CategoryInput in) {
        return BigDecimal.valueOf(in.done() * 100L)
                .divide(BigDecimal.valueOf(in.total()), 6, RoundingMode.HALF_UP);
    }
}
