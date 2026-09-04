package com.bvisionry.common.scoringconfig;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A pillar's maturity bands in ORDINAL ORDER — the single definition of "band
 * position" (agent-decisions RULING 4).
 *
 * <p>Lives in {@code common} because two slices must agree on it and the
 * ArchUnit ratchet forbids one importing the other: the {@code pipeline} slice
 * writes and resolves a position (course mapping, auto-enrolment) and the
 * {@code comparison} slice draws founders into band bars from the same
 * thresholds. Two copies of "sort by minimum score, tolerate legacy nulls" is
 * how a band stored at position 1 starts being resolved — or drawn — at
 * position 2, with nothing on screen showing the disagreement. Pure function;
 * no Spring, no entities.
 */
public final class MaturityBands {

    private MaturityBands() {
    }

    /**
     * The usable {@code (label -> [min, max])} entries of a pillar's
     * {@code maturity_thresholds_json}, sorted by minimum score — the only
     * ordering {@code MaturityThresholdValidator} guarantees is total (it
     * enforces a contiguous 1..N partition of 0-100) and the same order the
     * threshold editor renders. {@code jsonb} does not preserve key order, so
     * map iteration order is never trusted.
     *
     * <p>The filter checks the ELEMENTS, not just the size: the column predates
     * the validator, so legacy jsonb can hold {@code [null, 59]}, which the
     * comparator would unbox into an NPE. A null map (no thresholds) is empty.
     */
    public static List<Map.Entry<String, List<Integer>>> ordered(Map<String, List<Integer>> thresholds) {
        if (thresholds == null) {
            return List.of();
        }
        return thresholds.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().size() == 2
                        && e.getValue().get(0) != null && e.getValue().get(1) != null)
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, List<Integer>> e) -> e.getValue().get(0)))
                .toList();
    }
}
