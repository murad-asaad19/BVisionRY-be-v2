package com.bvisionry.catalog.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Catalog list item — the API contract's {@code CourseSummary}.
 *
 * <p>Field names are part of the public API contract consumed verbatim by the
 * Next.js web app; do not rename them.
 */
@Schema(name = "CourseSummary", description = "Catalog list item.")
public record CourseSummaryDto(

        @Schema(example = "9c1f0b2e-3a4d-4e5f-8a9b-0c1d2e3f4a5b")
        String id,

        @Schema(example = "leadership-essentials-new-managers")
        String slug,

        @Schema(example = "Leadership Essentials for New Managers")
        String title,

        @Schema(example = "Lead with confidence from day one.")
        String subtitle,

        @Schema(example = "Leadership")
        String category,

        @Schema(description = "Difficulty level.",
                allowableValues = {"BEGINNER", "INTERMEDIATE", "ADVANCED"},
                example = "INTERMEDIATE")
        String level,

        @Schema(description = "Audience / distribution channel.",
                allowableValues = {"EMPLOYEE", "PUBLIC", "B2B"},
                example = "PUBLIC")
        String mode,

        @Schema(description = "Price; null for free courses.", nullable = true, example = "149")
        BigDecimal price,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "Average rating 0–5.", example = "4.7")
        BigDecimal rating,

        @Schema(example = "238")
        int reviewsCount,

        @Schema(example = "5120")
        int learnersCount,

        @Schema(example = "12")
        int lessonsCount,

        @Schema(description = "Total duration in hours.", example = "6.5")
        BigDecimal durationHours,

        @Schema(example = "Dr. Amara Okafor")
        String instructorName,

        @Schema(example = "[\"leadership\", \"management\", \"communication\"]")
        List<String> tags,

        @Schema(description = "Enrollment policy.",
                allowableValues = {"OPEN", "INVITATION", "PAYMENT"},
                example = "OPEN")
        String enrollPolicy,

        @Schema(description = "Access tier.",
                allowableValues = {"EVERYONE", "SIGNED_IN", "ENROLLED", "LINK"},
                example = "EVERYONE")
        String visibility,

        @Schema(description = "CSS linear-gradient for the cover.",
                example = "linear-gradient(135deg, #0b1f3a 0%, #1d4ed8 100%)")
        String coverGradient) {
}
