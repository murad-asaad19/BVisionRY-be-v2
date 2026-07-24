package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;

import java.time.Instant;
import java.util.UUID;

/** One assignment row in the org console — provision (userId null) or member. */
public record ExerciseAssignmentResponse(
        UUID id,
        UUID templateId,
        String templateName,
        ExerciseTemplateStatus templateStatus,
        UUID organizationId,
        UUID userId,
        String userName,
        String userEmail,
        UUID assignedBy,
        Instant deadline,
        UUID submissionId,
        ExerciseSubmissionStatus submissionStatus,
        Instant submittedAt,
        long openCommentCount,
        Instant createdAt
) {}
