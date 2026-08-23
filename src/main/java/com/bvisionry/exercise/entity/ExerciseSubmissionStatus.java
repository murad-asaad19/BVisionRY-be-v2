package com.bvisionry.exercise.entity;

/**
 * Review-loop handshake for an exercise submission. Unlike assessment
 * submissions there is no AI evaluation — the loop is purely member ⇄ admin:
 * IN_PROGRESS → SUBMITTED → (CHANGES_REQUESTED → SUBMITTED)* → REVIEWED.
 * Editing rows is allowed in every state; the status only tracks whose turn
 * it is.
 */
public enum ExerciseSubmissionStatus {
    IN_PROGRESS,
    SUBMITTED,
    CHANGES_REQUESTED,
    REVIEWED,
    /**
     * The member never turned this in (V208) — a super admin's finding, set
     * only through the status override; the handshake never produces it.
     *
     * <p>The difference from {@link #IN_PROGRESS} is open vs closed, and it is
     * the AI narrative that needs it: an in-progress sheet is draft evidence,
     * a not-submitted one is an ABSENCE, and the prompt has to be able to say
     * which. Rows typed before it was closed are deliberately withheld from
     * the model — an approved narrative is member-visible, so it must never
     * quote text the founder never stood behind.
     *
     * <p>Not a lock: a member who turns up late can still submit out of it
     * (operator decision 2026-08-22), which corrects the record by itself.
     */
    NOT_SUBMITTED
}
