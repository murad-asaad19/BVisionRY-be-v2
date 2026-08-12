package com.bvisionry.programflow.dto;

/**
 * The unified member-facing status vocabulary across every task type (redesign
 * spec §2.1). Each type uses the subset that makes sense for it:
 *
 * <ul>
 *   <li>LESSON: NOT_STARTED / IN_PROGRESS / DONE</li>
 *   <li>COURSE: NOT_STARTED / IN_PROGRESS (+progressPct) / DONE</li>
 *   <li>EXERCISE: NOT_STARTED / IN_PROGRESS / SUBMITTED / CHANGES_REQUESTED / REVIEWED</li>
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
    DONE
}
