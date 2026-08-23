package com.bvisionry.programflow.dto;

/**
 * The unified member-facing status vocabulary across every task type (redesign
 * spec §2.1). Each type uses the subset that makes sense for it:
 *
 * <ul>
 *   <li>LESSON: NOT_STARTED / IN_PROGRESS / DONE</li>
 *   <li>COURSE: NOT_STARTED / IN_PROGRESS (+progressPct) / DONE</li>
 *   <li>EXERCISE: NOT_STARTED / IN_PROGRESS / SUBMITTED / CHANGES_REQUESTED / REVIEWED
 *       / NOT_SUBMITTED</li>
 *   <li>ASSESSMENT: NOT_STARTED / IN_PROGRESS / SUBMITTED / EVALUATED (+score)</li>
 *   <li>SURVEY: NOT_STARTED / DONE</li>
 * </ul>
 */
public enum JourneyTaskState {
    NOT_STARTED,
    IN_PROGRESS,
    SUBMITTED,
    CHANGES_REQUESTED,
    REVIEWED,
    EVALUATED,
    DONE,
    /**
     * An exercise a super admin closed as never handed in (V208). The record
     * is closed — nothing is left for the member to do — so it counts as done
     * ({@code ProgramRules.done}) and never holds the drip chain; the member
     * can still submit out of it later, which corrects the record by itself.
     */
    NOT_SUBMITTED
}
