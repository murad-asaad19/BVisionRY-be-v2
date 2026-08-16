package com.bvisionry.courseaccess.dto;

import com.bvisionry.common.enums.EnrollmentSource;
import com.bvisionry.courseaccess.domain.EffectiveCourseStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the member's Library / journey shelf, after the spec §3 merge:
 * one course, the STRONGEST source that put it there, and the §7b stamps.
 *
 * @param materialized false = covered by an org rule or an open AI suggestion
 *        with no enrollment row yet; the row is written when the member opens
 *        or accepts it.
 */
public record MemberCourseView(UUID courseId,
                               String courseTitle,
                               String courseSlug,
                               EnrollmentSource source,
                               EffectiveCourseStatus status,
                               boolean required,
                               Instant deadline,
                               boolean overdue,
                               int progressPct,
                               Instant assignedAt,
                               Instant completedAt,
                               String assignedByName,
                               String reason,
                               boolean materialized) {
}
