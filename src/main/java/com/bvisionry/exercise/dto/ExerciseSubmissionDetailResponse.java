package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;

import java.time.Instant;
import java.util.List;
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
        List<ExerciseRowResponse> rows,
        List<ExerciseCommentResponse> comments
) {}
