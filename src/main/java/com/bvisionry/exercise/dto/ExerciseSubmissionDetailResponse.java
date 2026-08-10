package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the sheet editor (member) or review screen (admin) needs in one
 * round-trip: template + columns, live rows, and the full comment list.
 * {@code memberName}/{@code memberEmail} are only populated for admin viewers.
 */
public record ExerciseSubmissionDetailResponse(
        UUID submissionId,
        UUID assignmentId,
        UUID templateId,
        String templateName,
        String templateDescription,
        ExerciseSubmissionStatus status,
        Instant deadline,
        Instant lastSavedAt,
        Instant submittedAt,
        Instant reviewedAt,
        String memberName,
        String memberEmail,
        List<ExerciseColumnResponse> columns,
        /** Read-only sample row (columnId → value) shown above the sheet, or null. */
        Map<String, Object> exampleRow,
        /** False = no member-added rows; the sheet is fixed to its starter rows. */
        boolean allowAddRows,
        List<ExerciseRowResponse> rows,
        List<ExerciseCommentResponse> comments,
        /**
         * Quality tag (spec §4/§11) — <strong>staff only</strong>: all five
         * fields are null/empty for the member's own sheet. The tag is the
         * reviewer's private metadata about the work, never part of the
         * participation score.
         */
        String qualityTagKey,
        /** Label snapshot from tagging time (§7 stable keys), not the live label. */
        String qualityTagLabel,
        Instant qualityTaggedAt,
        /** §7b attribution; null once that reviewer is erased. */
        String qualityTaggedByName,
        /** The live §7 tag set, so the reviewer needs no platform-config read. */
        List<ExerciseQualityTagOption> qualityTagOptions
) {

    /**
     * One selectable pill. Named for the exercise slice on purpose: OpenAPI keys
     * components by SIMPLE name, and {@code QualityTag} already belongs to the
     * platform config payload.
     */
    public record ExerciseQualityTagOption(String key, String label) {}
}
