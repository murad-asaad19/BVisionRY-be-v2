package com.bvisionry.common.enums;

/**
 * Why a submission's evaluation ended in a failed/degraded state — drives the
 * respondent retake flow.
 *
 * <ul>
 *   <li>{@link #SYSTEM} — the AI/infra step failed (model error, parse/validation,
 *       or a degraded NEEDS_REVIEW run). The respondent's answers are valid, so a
 *       retake simply re-runs the evaluation on the same answers.</li>
 *   <li>{@link #INPUT} — the answers themselves need changing before re-evaluation.
 *       Forward-looking: there is no current producer because {@code submit} validates
 *       required questions up front. A retake unlocks editing instead of re-running.</li>
 * </ul>
 *
 * <p>A {@code null} value (historical rows, or any failure recorded before this was
 * introduced) is treated as {@link #SYSTEM} by the retake flow.
 */
public enum SubmissionFailureKind {
    SYSTEM,
    INPUT
}
