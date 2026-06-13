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
    PENDING_REEDIT
}
