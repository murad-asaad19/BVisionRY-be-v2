package com.bvisionry.enrollment.dto;

import java.time.OffsetDateTime;

/**
 * Compact enrollment representation returned by enroll and list endpoints.
 */
public record EnrollmentDto(
        String id,
        String courseId,
        String courseTitle,
        String courseSlug,
        String status,
        int progressPct,
        OffsetDateTime enrolledAt,
        OffsetDateTime completedAt) {
}
