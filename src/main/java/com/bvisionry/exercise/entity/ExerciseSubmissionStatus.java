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
    REVIEWED
}
