package com.bvisionry.notification.push;

import java.util.UUID;

/**
 * Native-query projection of one founder who is due an inactivity nudge, with
 * the enrolment they have stalled on.
 *
 * <p>A projection over a native query — not entities — for the same reason
 * {@code OrgMemberRow} is one in the programflow slice: the answer spans
 * {@code enrollment}, {@code content_progress}, {@code course},
 * {@code users} and {@code organizations}, and importing four other features'
 * entities to assemble it in Java would be four new cross-feature dependencies
 * (ArchitectureRulesTest rule 1) for a read this package never mutates.
 */
public interface StalledLearnerRow {

    /** The founder to nudge. */
    UUID getUserId();

    /** Course title, straight from {@code course.title} (varchar 200). */
    String getCourseTitle();

    /** Course slug — the deep link is {@code /app/courses/<slug>}. */
    String getCourseSlug();

    /** Whole days since the last recorded progress event on that enrolment. */
    int getStalledDays();
}
