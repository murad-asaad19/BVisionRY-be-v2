package com.bvisionry.catalog.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Full editable course detail for the authoring editor — returned by
 * {@code GET /api/v1/admin/courses/{slug}}.
 *
 * <p>Unlike the public {@code CourseDetailDto} this works for courses in ANY
 * state (DRAFT/PUBLISHED/ARCHIVED), exposes the raw enum + lifecycle fields the
 * settings form edits (state, audience, access, enrollPolicy, visibility,
 * certification, instructor title/bio), and carries the full per-lesson payload
 * ({@code body}/{@code videoUrl}/{@code assetUrl}/{@code pipelineId}) the lesson
 * editor needs — so opening a lesson no longer loses its content.
 *
 * <p>Media values are returned RAW (a {@code minio://} marker or an external
 * URL); the player resolves markers to presigned URLs at playback time.
 */
@Schema(name = "AdminCourseDetail", description = "Full editable course detail for the authoring editor.")
public record AdminCourseDetailDto(
        String id,
        String slug,
        String title,
        String subtitle,
        String category,
        String description,
        String level,
        String mode,
        String audience,
        String access,
        String enrollPolicy,
        String visibility,
        String state,
        BigDecimal price,
        String currency,
        BigDecimal durationHours,
        String coverGradient,
        String coverImageUrl,
        String certificationTitle,
        Integer certificationPassingPct,
        String instructorName,
        String instructorTitle,
        String instructorBio,
        List<String> outcomes,
        List<String> tags,
        List<Section> sections,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record Section(String id, String title, int sequence, List<Lesson> lessons) {
    }

    public record Lesson(
            String id,
            String title,
            String contentType,
            int sequence,
            Integer durationMin,
            boolean allowPreview,
            String body,
            String videoUrl,
            String assetUrl,
            String pipelineId) {
    }
}
