package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExerciseTemplateDetailResponse(
        UUID id,
        String name,
        /** Serialised tiptap document (see {@link ExerciseTemplate#getDescription()}). */
        String description,
        /**
         * The cover EXACTLY as stored — a {@code minio://bucket/key} marker or
         * an external URL. The admin form edits this field and sends it back,
         * so it must round-trip: writing a resolved presigned URL here would
         * persist a link that expires within the hour.
         */
        String coverImageUrl,
        /** The same cover, resolved for display. Never sent back. */
        String coverImageDisplayUrl,
        /** Staff-only brief for the AI — admin surface only, never in member payloads. */
        String aiContext,
        ExerciseTemplateStatus status,
        List<ExerciseColumnResponse> columns,
        /** Read-only sample row (columnId → value) shown above the sheet, or null. */
        Map<String, Object> exampleRow,
        /** Rows seeded into every new member submission, or null. */
        List<Map<String, Object>> starterRows,
        /** False = the sheet is fixed to the starter rows. */
        boolean allowAddRows,
        /** True once the template has any assignment — column add/delete is frozen. */
        boolean structureLocked,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * @param coverImageDisplayUrl the stored cover put through
     *                             {@link com.bvisionry.common.media.MediaUrlPort}
     *                             — a raw {@code minio://} marker is not loadable
     *                             by a browser, but is the only safe thing to
     *                             store, so both travel.
     */
    public static ExerciseTemplateDetailResponse from(ExerciseTemplate template,
            boolean structureLocked, String coverImageDisplayUrl) {
        return new ExerciseTemplateDetailResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getCoverImageUrl(),
                coverImageDisplayUrl,
                template.getAiContext(),
                template.getStatus(),
                template.getColumns().stream().map(ExerciseColumnResponse::from).toList(),
                template.getExampleRow(),
                template.getStarterRows(),
                template.isAllowAddRows(),
                structureLocked,
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
