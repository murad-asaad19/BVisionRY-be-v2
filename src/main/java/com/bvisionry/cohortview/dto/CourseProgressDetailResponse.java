package com.bvisionry.cohortview.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One member's progress through one course, lesson by lesson — the admin's
 * per-member course screen.
 *
 * <p><strong>An un-enrolled member still gets the skeleton.</strong> A course
 * covered by an org rule the member never opened has no {@code enrollment} row,
 * which is a state, not an error: sections and lessons come back with
 * {@code completed} false everywhere and {@code status}/{@code progressPct}
 * null. Only a course that does not exist (or the org cannot see) is a 404.
 */
public record CourseProgressDetailResponse(
        UUID courseId,
        String title,
        /** The enrolment's status; null when the member has no enrolment row. */
        String status,
        /** Live completion percent (never the cached column); null with no enrolment. */
        Integer progressPct,
        Instant enrolledAt,
        Instant completedAt,
        boolean certificateIssued,
        List<CourseSection> sections) {

    public record CourseSection(UUID sectionId, String title, int position,
                                List<CourseLesson> lessons) {}

    public record CourseLesson(UUID contentId, String title, int position, boolean completed,
                               Instant completedAt) {}
}
