package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.entity.RespondentFieldMode;
import com.bvisionry.exercise.entity.WorksheetBlock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExerciseTemplateDetailResponse(
        UUID id,
        String name,
        ExerciseTemplateKind kind,
        /** WORKSHEET only: the ordered block list. Null for sheets. */
        List<WorksheetBlock> blocks,
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
        /** Public link open? Only meaningful together with a PUBLISHED status. */
        boolean isPublic,
        /** Null until the exercise has been made public once; stable thereafter. */
        UUID publicToken,
        RespondentFieldMode respondentNameMode,
        RespondentFieldMode respondentEmailMode,
        /** How many anonymous fills the public link has collected. */
        long publicResponseCount,
        /** Survey paired to the public thank-you screen, or null. */
        UUID postCompletionSurveyId,
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
            boolean structureLocked, String coverImageDisplayUrl, long publicResponseCount) {
        return new ExerciseTemplateDetailResponse(
                template.getId(),
                template.getName(),
                template.getKind(),
                template.getBlocks(),
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
                template.isPublic(),
                template.getPublicToken(),
                template.getRespondentNameMode(),
                template.getRespondentEmailMode(),
                publicResponseCount,
                template.getPostCompletionSurveyId(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
