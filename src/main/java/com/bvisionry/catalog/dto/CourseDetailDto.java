package com.bvisionry.catalog.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Full course view — the API contract's {@code CourseDetail}, which is
 * {@code CourseSummary} extended with description, outcomes, instructor bio,
 * sections/lessons, reviews and an optional certification.
 *
 * <p>The summary fields are flattened (rather than nested) to match the
 * contract's {@code CourseDetail = CourseSummary & {…}} intersection shape.
 * Field names are consumed verbatim by the web app — do not rename.
 */
@Schema(name = "CourseDetail", description = "Full course view (summary plus detail).")
public record CourseDetailDto(

        // --- CourseSummary fields (flattened) ---
        String id,
        String slug,
        String title,
        String subtitle,
        String category,
        @Schema(allowableValues = {"BEGINNER", "INTERMEDIATE", "ADVANCED"})
        String level,
        @Schema(allowableValues = {"EMPLOYEE", "PUBLIC", "B2B"})
        String mode,
        @Schema(nullable = true)
        BigDecimal price,
        String currency,
        BigDecimal rating,
        int reviewsCount,
        int learnersCount,
        int lessonsCount,
        BigDecimal durationHours,
        String instructorName,
        List<String> tags,
        @Schema(allowableValues = {"OPEN", "INVITATION", "PAYMENT"})
        String enrollPolicy,
        @Schema(allowableValues = {"EVERYONE", "SIGNED_IN", "ENROLLED", "LINK"})
        String visibility,
        String coverGradient,

        // --- CourseDetail-only fields ---
        @Schema(description = "Long-form description.")
        String description,

        @Schema(description = "What the learner will be able to do.")
        List<String> outcomes,

        Instructor instructor,

        List<Section> sections,

        List<Review> reviews,

        @Schema(nullable = true, description = "Awarded certification, or null.")
        Certification certification) {

    /** Instructor profile shown on the detail page. */
    @Schema(name = "CourseDetailInstructor")
    public record Instructor(
            @Schema(example = "Dr. Amara Okafor") String name,
            @Schema(example = "Leadership Coach & Former VP of People") String title,
            @Schema(example = "Amara has coached 500+ first-time managers…") String bio) {
    }

    /** An ordered section grouping lessons. */
    @Schema(name = "CourseDetailSection")
    public record Section(
            String id,
            @Schema(example = "Getting Started") String title,
            List<Lesson> lessons) {
    }

    /** A single lesson within a section. */
    @Schema(name = "CourseDetailLesson")
    public record Lesson(
            String id,
            @Schema(example = "Welcome & course overview") String title,
            @Schema(description = "Lesson content type.",
                    allowableValues = {"VIDEO", "PDF", "QUIZ", "CERTIFICATION",
                            "PAGE", "DOCUMENT", "SCORM", "LINK", "IMAGE"},
                    example = "VIDEO")
            String type,
            @Schema(description = "Length in minutes.", example = "8") int durationMin,
            @Schema(description = "Free preview lesson?", example = "true") boolean preview) {
    }

    /** A published learner review. */
    @Schema(name = "CourseDetailReview")
    public record Review(
            @Schema(example = "Priya N.") String author,
            @Schema(example = "5") int rating,
            @Schema(example = "Practical and immediately useful.") String comment) {
    }

    /** The certification a learner earns on completion. */
    @Schema(name = "CourseDetailCertification")
    public record Certification(
            @Schema(example = "Certified New Manager") String title,
            @Schema(description = "Passing score percentage.", example = "80") int passingPct) {
    }
}
