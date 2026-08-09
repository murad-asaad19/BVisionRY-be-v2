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
    /** References a workshop; done = a completed workshop task submission. */
    WORKSHOP,
    /** References a survey; done = a survey response by the member. */
    SURVEY;

    /**
     * Can a member currently bring this task to a done-state inside the app?
     * False ONLY for SURVEY today: there is no member survey-response route
     * yet, so a survey task must neither block sequential drip nor count in
     * any completion denominator (journey progress, pulse, engagement) — it
     * renders, and flips to done if a response happens to exist. D2 adds the
     * member survey route and flips this to true.
     */
    public boolean completableInApp() {
        return this != SURVEY;
    }
}
