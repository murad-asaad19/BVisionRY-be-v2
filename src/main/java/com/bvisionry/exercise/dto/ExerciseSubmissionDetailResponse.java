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
        List<ExerciseCommentResponse> comments
) {}
