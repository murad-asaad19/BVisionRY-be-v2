package com.bvisionry.courseaccess.domain;

/**
 * The spec §3 status vocabulary — {@code suggested | assigned | in_progress |
 * completed} — as the READ model states it.
 *
 * <p>Deliberately NOT a widening of {@code EnrollmentStatus}
 * (ACTIVE/COMPLETED/SUSPENDED/CANCELLED). That column is read by eight raw-SQL
 * queries across six slices, all of which spell "is this person on this course"
 * as {@code status <> 'CANCELLED'}; adding SUGGESTED there would have made every
 * one of them silently treat a suggestion as an enrollment. A suggestion is not
 * an enrollment — it has no progress, no certificate and no seat — so it is
 * recorded on the {@code auto_enrolments} ledger (outcome {@code SUGGESTED}) and
 * becomes an enrollment only when the member accepts.
 */
public enum EffectiveCourseStatus {

    /** AI Suggest mode: offered, not yet taken. One tap away from ASSIGNED. */
    SUGGESTED,

    /** The member has it and has not opened it (or has no enrollment row yet). */
    ASSIGNED,

    /** Started, unfinished. */
    IN_PROGRESS,

    /** {@code enrollment.status = 'COMPLETED'}. */
    COMPLETED
}
