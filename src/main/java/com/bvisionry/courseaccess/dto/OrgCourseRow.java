package com.bvisionry.courseaccess.dto;

import com.bvisionry.common.enums.EnrollmentSource;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the org admin's Courses tab (spec §2.3): course x source x
 * audience x completion.
 *
 * <p>There is one row PER SOURCE, not per course — an org rule and a handful of
 * by-name assignments for the same course are two different things to remove,
 * and folding them would leave the row menu unable to say which.
 *
 * @param audience human copy, already resolved ("Everyone (auto, incl. new members)").
 * @param visible  the course is still visible to this org. False = the platform
 *                 pulled it (or the org's tier dropped): progress is kept, the
 *                 assignment is read-only, and no new content opens.
 */
public record OrgCourseRow(UUID courseId,
                           String courseTitle,
                           EnrollmentSource source,
                           String audience,
                           boolean required,
                           Instant deadline,
                           int learners,
                           int completed,
                           int completionPct,
                           int suggestedPending,
                           boolean visible,
                           Instant assignedAt,
                           String assignedByName) {
}
