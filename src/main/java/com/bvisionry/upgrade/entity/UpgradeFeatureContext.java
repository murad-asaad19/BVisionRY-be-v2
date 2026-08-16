package com.bvisionry.upgrade.entity;

/**
 * Which paywalled surface the user was looking at when they hit the gate.
 * Closed enum (not a free string) so the platform-side demand rollups stay
 * clean — typos can't fragment "ORG_INSIGHTS" into three half-buckets.
 */
public enum UpgradeFeatureContext {
    INSIGHTS("Insights"),
    ORG_INSIGHTS("Org Insights"),
    BENCHMARKS("Benchmarks"),
    ROI_REPORT("ROI Reporting"),
    /** The quota-blocked cohort Launch button (redesign spec §8). */
    COHORT_QUOTA("Cohort launches"),
    OTHER("General");

    private final String label;

    UpgradeFeatureContext(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
