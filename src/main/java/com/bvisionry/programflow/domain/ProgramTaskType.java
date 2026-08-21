package com.bvisionry.programflow.domain;

/**
 * What kind of work a {@link ProgramTask} represents (redesign spec §1).
 * {@code LESSON} is the original multi-step form flow and keeps its fields;
 * every other type references the owning slice's object via
 * {@link ProgramTask#getRefId()} and carries no form fields.
 */
public enum ProgramTaskType {
    /** The existing in-place form task (fields + program submissions). */
    LESSON,
    /** References a global course; member state = their enrollment. */
    COURSE,
    /** References an exercise template; member state = their exercise submission. */
    EXERCISE,
    /** References a pipeline; member state = the submission tagged to this task. */
    ASSESSMENT,
    /** References a survey; done = a survey response by the member. */
    SURVEY;

    /**
     * Can a member currently bring this task to a done-state inside the app?
     * True for every type since D2 shipped the member survey-response route
     * ({@code /api/my/program/tasks/{id}/survey}) — every task now blocks
     * sequential drip and counts in every completion denominator (journey
     * progress, pulse, engagement, continue-cursor). The predicate stays so a
     * future not-yet-completable type flips ONE switch instead of re-auditing
     * five call sites.
     */
    public boolean completableInApp() {
        return true;
    }
}
