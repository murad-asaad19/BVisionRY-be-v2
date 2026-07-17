package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/** One card in the member's "My exercises" list. */
public record MyExerciseSummaryResponse(
        UUID submissionId,
        UUID templateId,
        String templateName,
        String templateDescription,
        ExerciseSubmissionStatus status,
        Instant deadline,
        long openCommentCount,
        Instant lastSavedAt,
        Instant submittedAt
) {}
