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
    SURVEY,
    /**
     * A coaching session or workshop the cohort holds (sessions spec v2 §2–§3).
     * No ref: the session subtype ({@link ProgramTask#getSessionType()}),
     * duration and the optional post-session survey live on the task row. A
     * 1:1 task is one BOOKING per member, booked by the member; a group or
     * workshop task is ONE cohort-wide session the coach schedules. Member
     * state = the {@code sessions} row tagged with this task; done = it was
     * held and the member has an attendance row.
     */
    SESSION;

    /**
     * Can a member currently bring this task to a done-state inside the app?
     * True for every type but SESSION: a session is completed by attendance —
     * the auto-complete job's or the coach's mark, not anything the member can
     * do — so it must never hold the sequential drip or count in a completion
     * denominator (journey progress, pulse, engagement, continue-cursor) —
     * spec §1.6. The SQL twin is {@code TaskCompletion.COUNTS_FOR_USER};
     * change one, change the other.
     */
    public boolean completableInApp() {
        return this != SESSION;
    }
}
