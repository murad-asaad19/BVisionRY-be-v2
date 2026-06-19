package com.bvisionry.common.enums;

public enum SubmissionStatus {
    IN_PROGRESS,
    SUBMITTED,
    EVALUATED,
    FAILED,
    /**
     * An admin has unlocked one or more pillars on this previously-EVALUATED
     * submission, allowing the member to edit answers for those pillars only.
     * On re-submit the submission transitions SUBMITTED → EVALUATED via the
     * partial-re-evaluation path, which re-evaluates only the unlocked pillars
     * and regenerates the OverallSummary.
     */
    PENDING_REEDIT,

    /**
     * The AI evaluation ran to completion but one or more pillars (or the overall
     * summary) could not produce valid output even after the engine's repair
     * retries. Partial results are persisted and visible to admins, the failed
     * pillars are flagged on {@code pillar_evaluations.ai_failed}, and an admin
     * can retry. This exists so a parse/validation failure is never silently
     * persisted as a clean EVALUATED row with zeroed scores — fail loud, not quiet.
     */
    NEEDS_REVIEW
}
