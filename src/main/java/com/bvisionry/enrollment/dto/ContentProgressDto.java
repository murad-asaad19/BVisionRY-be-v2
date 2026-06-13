package com.bvisionry.enrollment.dto;

import java.time.OffsetDateTime;

/**
 * Per-lesson progress record returned inside the learn view.
 */
public record ContentProgressDto(
        String contentId,
        boolean completed,
        OffsetDateTime completedAt) {
}
