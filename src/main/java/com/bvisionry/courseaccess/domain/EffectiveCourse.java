package com.bvisionry.courseaccess.domain;

import com.bvisionry.common.enums.EnrollmentSource;

import java.time.Instant;
import java.util.UUID;

/**
 * ONE course as one member actually experiences it, after the four paths of
 * spec §3 have been merged and deduped.
 *
 * @param materialized an {@code enrollment} row exists. False means the member
 *        is covered by an org rule or holds an open AI suggestion and nothing
 *        has been written yet — the row is created lazily on first open (the D1
 *        {@code ensureEnrollment} pattern), so this read must union the two.
 * @param reason for AI rows, the pillar that asked for it; else null.
 */
public record EffectiveCourse(UUID courseId,
                              String courseTitle,
                              String courseSlug,
                              EnrollmentSource source,
                              EffectiveCourseStatus status,
                              boolean required,
                              Instant deadline,
                              int progressPct,
                              Instant assignedAt,
                              Instant completedAt,
                              String assignedByName,
                              String reason,
                              boolean materialized) {

    /** Past its deadline and not finished — the journey chip and the needs-attention strip. */
    public boolean overdue(Instant now) {
        return deadline != null && status != EffectiveCourseStatus.COMPLETED && deadline.isBefore(now);
    }
}
