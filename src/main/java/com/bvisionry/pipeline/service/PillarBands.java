package com.bvisionry.pipeline.service;

import com.bvisionry.pipeline.entity.Pillar;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A pillar's maturity bands in ORDINAL ORDER — the single definition of
 * "band position" (agent-decisions RULING 4), shared by the config surface that
 * writes a position ({@link PillarCourseMappingService}) and the engine that
 * reads one back ({@link AutoEnrolmentService}).
 *
 * <p>It lives in one place on purpose. Both sides key on the same 0-based index
 * into the same ordering, and two copies of "sort by minimum score, tolerate
 * legacy nulls" is how a rule stored at position 1 starts being resolved as
 * position 2 — a silent mis-enrolment nothing on screen would show.
 */
final class PillarBands {

    private PillarBands() {
    }

    /** One band of a pillar, resolved from its thresholds at its ordinal position. */
    record Band(String label, int min, int max) {
    }

    /**
     * The pillar's bands in ordinal order — sorted by minimum score, which is the
     * only ordering {@code MaturityThresholdValidator} guarantees is total (it
     * enforces a contiguous 1..N partition of 0-100) and the same order the
     * threshold editor renders. {@code jsonb} does not preserve key order, so map
     * iteration order is never trusted.
     *
     * <p>The filter checks the ELEMENTS, not just the size: the column predates the
     * validator, so legacy jsonb can hold {@code [null, 59]}, which the comparator
     * would unbox into an NPE.
     */
    static List<Band> ordered(Pillar pillar) {
        Map<String, List<Integer>> thresholds = pillar.getMaturityThresholds();
        if (thresholds == null) {
            return List.of();
        }
        return thresholds.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().size() == 2
                        && e.getValue().get(0) != null && e.getValue().get(1) != null)
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, List<Integer>> e) -> e.getValue().get(0)))
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
