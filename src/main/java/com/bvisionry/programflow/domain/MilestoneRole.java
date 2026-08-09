package com.bvisionry.programflow.domain;

/**
 * The journey-milestone role of an ASSESSMENT task (redesign spec §5).
 * Check-ins are milestones between modules; BASELINE and DISTANCE are the
 * cohort's designated comparison anchors — at most one of each per cohort.
 * Null (and only null) for every non-ASSESSMENT task type.
 */
public enum MilestoneRole {
    BASELINE,
    CHECKIN,
    DISTANCE
}
