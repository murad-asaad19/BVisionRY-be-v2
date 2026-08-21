package com.bvisionry.pipeline.entity;

/**
 * What the auto-enrolment engine actually DID with one (founder, course) pair of
 * one evaluation. Every decision is recorded, including the two that write no
 * enrolment — an operator asking "why wasn't X enrolled?" gets a row, not a
 * silence.
 *
 * <p>Mirrored by the {@code ck_auto_enrolments_outcome} CHECK in V151.
 */
public enum AutoEnrolmentOutcome {

    /** A new {@code enrollment} row was written for this founder. */
    ENROLLED,

    /**
     * The founder was already enrolled in this course — from an earlier
     * evaluation, or because they enrolled themselves. Left exactly as it was:
     * {@code auto_enrolment_on_reassessment: NEW_ENROLMENTS_ONLY} never duplicates
     * an enrolment and never resets one's progress.
     */
    ALREADY_ENROLLED,

    /**
     * The mapped course is DRAFT or ARCHIVED, so nobody was enrolled in it. The
     * mapping surface deliberately does not filter by state — an admin may point a
     * band at a course they are still writing — so the refusal is the engine's, and
     * it is a recorded skip rather than an error: one unpublished course must not
     * cost the founder the rest of their journey.
     */
    COURSE_NOT_PUBLISHED,

    /**
     * The rule is in {@link PillarCourseMode#SUGGEST} mode: the founder is being
     * OFFERED this course, not put on it. No {@code enrollment} row exists — the
     * ledger row IS the suggestion, and it carries the pillar that asked for it
     * so the card can say why. One tap turns it into an enrollment and stamps
     * {@code accepted_at} (spec §7b).
     *
     * <p>The fourth value V151's CHECK reserved to a human. It is here rather
     * than as an {@code EnrollmentStatus} because eight raw-SQL reads across six
     * slices spell "is this person on this course" as
     * {@code status <> 'CANCELLED'}; a SUGGESTED enrollment would have silently
     * counted as one in every single one of them.
     */
    SUGGESTED
}
