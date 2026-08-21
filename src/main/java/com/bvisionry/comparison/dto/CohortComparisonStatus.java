package com.bvisionry.comparison.dto;

import java.util.List;
import java.util.UUID;

/**
 * How many enrolled founders of a cohort are READY for a distance comparison
 * (both sides evaluated) but have no stored one yet — derived, never stored.
 *
 * <p>Deliberately worded "not computed", not "failed": a founder whose distance
 * evaluated seconds ago legitimately lands here for a moment, and the remedy
 * (recompute) is idempotent either way.
 */
public record CohortComparisonStatus(int notComputed, List<PendingFounder> founders) {

    public record PendingFounder(UUID userId, String name) {
    }
}
