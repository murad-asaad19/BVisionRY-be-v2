package com.bvisionry.catalog.dto.authoring;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a course (course-level metadata authoring).
 *
 * <p>Enum-valued fields ({@code level}, {@code mode}, {@code audience},
 * {@code access}, {@code enrollPolicy}, {@code visibility}) are accepted as raw
 * strings and parsed leniently in the service — unknown/blank values fall back
 * to the column default rather than 400-ing, keeping the authoring form forgiving.
 * {@code state} is NOT set here; use the dedicated state-transition endpoint.
 */
public record UpsertCourseRequest(
        @NotBlank @Size(max = 160) String slug,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 300) String subtitle,
        @Size(max = 80) String category,
        String level,
        String mode,
        String audience,
        String access,
        String description,
        @DecimalMin(value = "0", message = "price must be >= 0") BigDecimal price,
        @Size(max = 3) String currency,
        @DecimalMin(value = "0", message = "durationHours must be >= 0") BigDecimal durationHours,
        @Size(max = 200) String certificationTitle,
        @Min(0) @Max(100) Integer certificationPassingPct,
        @Size(max = 160) String instructorName,
        @Size(max = 200) String instructorTitle,
        String instructorBio,
        String enrollPolicy,
        String visibility,
        @Size(max = 120) String coverGradient,
        @Size(max = 500) String coverImageUrl,
        List<String> outcomes,
        List<String> tags) {
}
