package com.bvisionry.pipeline.entity;

/**
 * Spec §3: what an AI course rule DOES when a founder lands in its band.
 *
 * <p>Column {@code pillar_course_mappings.mode} (V168). Existing rows default to
 * {@link #AUTO_ASSIGN} — that is exactly what the engine has always done, so the
 * migration changes no behaviour.
 */
public enum PillarCourseMode {

    /**
     * A recommendation with one-tap Accept (My Growth, founder profile, Library,
     * journey right rail). Recorded on the ledger as
     * {@link AutoEnrolmentOutcome#SUGGESTED}; NO enrollment row exists until the
     * founder accepts.
     */
    SUGGEST,

    /** Creates the enrollment on evaluation — the pre-V168 behaviour. */
    AUTO_ASSIGN
}
