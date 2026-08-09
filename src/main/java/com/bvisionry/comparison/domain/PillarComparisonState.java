package com.bvisionry.comparison.domain;

/**
 * The state of one pillar row in a stored comparison. Dropped or added pillars
 * are never silently omitted (spec §5) — they carry one of the one-sided
 * states instead.
 */
public enum PillarComparisonState {
    /** Both sides measured — full before/after/delta. */
    MAPPED,
    /** Measured by the distance assessment only — no baseline to compare against. */
    NEWLY_MEASURED,
    /** Measured at baseline but not by the distance assessment. */
    NOT_REMEASURED
}
