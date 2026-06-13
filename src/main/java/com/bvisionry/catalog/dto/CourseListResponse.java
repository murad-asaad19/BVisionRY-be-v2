package com.bvisionry.catalog.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope for {@code GET /api/v1/courses} — the API contract's
 * {@code { items: CourseSummary[], total: number }}.
 */
@Schema(name = "CourseListResponse", description = "Catalog listing envelope.")
public record CourseListResponse(

        @Schema(description = "Matching courses.")
        List<CourseSummaryDto> items,

        @Schema(description = "Total number of matching courses.", example = "9")
        long total) {
}
