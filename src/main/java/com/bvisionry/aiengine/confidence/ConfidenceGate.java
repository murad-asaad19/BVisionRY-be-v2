package com.bvisionry.aiengine.confidence;

import com.bvisionry.common.dto.PillarEvaluationResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Decides when a pillar score is low-confidence enough to warrant spending extra
 * accuracy budget (escalation), and aggregates the resulting samples.
 *
 * <p>The confidence signal is deliberately <b>model-agnostic and heuristic</b>:
 * it does not rely on the model self-reporting a confidence field (weaker models
 * won't), only on where the score lands. A score sitting within a small margin of
 * a maturity-threshold boundary is exactly where a one-or-two-point wobble flips
 * the maturity label — the place extra sampling pays off. Mid-band scores are
 * treated as confident and skip escalation, so the cost is paid only where it
 * changes the outcome.
 */
@Component
public class ConfidenceGate {

    /**
     * True if {@code score} sits within {@code margin} points of any interior
     * maturity-band boundary (band endpoints other than 0 and 100). Thresholds are
     * the per-pillar maturity map: label → [lo, hi].
     */
    public boolean isBorderline(BigDecimal score, Map<String, ? extends List<Integer>> thresholds, int margin) {
        if (score == null || thresholds == null || thresholds.isEmpty()) {
            return false;
        }
        int s = score.intValue();
        for (List<Integer> band : thresholds.values()) {
            if (band == null || band.size() < 2) {
                continue;
            }
            int lo = band.get(0);
            int hi = band.get(1);
            if (lo > 0 && Math.abs(s - lo) <= margin) {
                return true;
            }
            if (hi < 100 && Math.abs(s - hi) <= margin) {
                return true;
            }
        }
        return false;
    }

    /** Median of a non-empty score list (lower-mid for even counts). */
    public int median(List<Integer> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = scores.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /**
     * Choose the sample whose score is closest to {@code target} (the median),
     * so the representative narrative/evidence matches the consensus score rather
     * than an outlier. Ties resolve to the earliest sample.
     */
    public PillarEvaluationResult closestToScore(List<PillarEvaluationResult> results, int target) {
        return results.stream()
                .min(Comparator.comparingInt(r -> Math.abs(r.scorePercentage() - target)))
                .orElse(null);
    }
}
