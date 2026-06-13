package com.bvisionry.catalog.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Admin/authoring list item — like {@link CourseSummaryDto} but includes the
 * lifecycle {@code state} (so DRAFT/ARCHIVED courses are visible) and authoring
 * counts. Returned by {@code GET /api/v1/admin/courses}.
 */
@Schema(name = "CourseAdmin", description = "Authoring list item (includes DRAFT/ARCHIVED).")
public record CourseAdminDto(
        String id,
        String slug,
        String title,
        String subtitle,
        String category,
        String level,
        String mode,
        String audience,
        String access,
        @Schema(allowableValues = {"DRAFT", "PUBLISHED", "ARCHIVED"}) String state,
        BigDecimal price,
        String currency,
        BigDecimal durationHours,
        int lessonsCount,
        int learnersCount,
        int sectionsCount,
        String instructorName,
        String coverGradient,
        String coverImageUrl,
        List<String> tags,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
