package com.bvisionry.pipeline.service;

import com.bvisionry.common.scoringconfig.MaturityBands;
import com.bvisionry.pipeline.entity.Pillar;

import java.util.List;

/**
 * A pillar's maturity bands in ORDINAL ORDER — the single definition of
 * "band position" (agent-decisions RULING 4), shared by the config surface that
 * writes a position ({@link PillarCourseMappingService}) and the engine that
 * reads one back ({@link AutoEnrolmentService}).
 *
 * <p>The ordering rule itself is {@link MaturityBands} in {@code common}, so the
 * comparison slice's band bars read the same axis without a cross-feature
 * import. Both sides key on the same 0-based index into the same ordering, and
 * two copies of "sort by minimum score, tolerate legacy nulls" is how a rule
 * stored at position 1 starts being resolved as position 2 — a silent
 * mis-enrolment nothing on screen would show.
 */
final class PillarBands {

    private PillarBands() {
    }

    /** One band of a pillar, resolved from its thresholds at its ordinal position. */
    record Band(String label, int min, int max) {
    }

    /** The pillar's bands in ordinal order — {@link MaturityBands#ordered} over its thresholds. */
    static List<Band> ordered(Pillar pillar) {
        return MaturityBands.ordered(pillar.getMaturityThresholds()).stream()
                .map(e -> new Band(e.getKey(), e.getValue().get(0), e.getValue().get(1)))
                .toList();
    }

    /**
     * The 0-based ordinal position of the band this STORED maturity label names, or
     * {@code -1} when it names none of the pillar's CURRENT bands.
     *
     * <p>Matched by exact label, because that is exactly how the label got there:
     * {@code ScoringService#deriveMaturityLabel} returns the threshold map's own key
     * verbatim. No trimming, no case folding — a fuzzy match on a per-customer
     * vocabulary invents a band the admin did not configure, and two keys differing
     * only in case would resolve ambiguously.
     *
     * <p>{@code -1} is a legitimate, expected answer, not an error:
     * <ul>
     *   <li>a pillar whose evaluation failed carries {@code "Unknown"};</li>
     *   <li>a pillar whose band set was re-labelled after the founder was measured
     *       carries a name that no longer exists.</li>
     * </ul>
     * Both are OFF-AXIS. The caller does nothing for that pillar — the competency
     * matrix is where an off-axis founder surfaces; the engine must not guess a
     * neighbouring band on their behalf.
     */
    static int positionOf(Pillar pillar, String maturityLabel) {
        if (maturityLabel == null) {
            return -1;
        }
        List<Band> bands = ordered(pillar);
        for (int i = 0; i < bands.size(); i++) {
            if (bands.get(i).label().equals(maturityLabel)) {
                return i;
            }
        }
        return -1;
    }
}
