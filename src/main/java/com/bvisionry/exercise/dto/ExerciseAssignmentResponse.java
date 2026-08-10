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
        /**
         * When changes were last requested on this submission; null on one that
         * never came back for a second pass. Durable (never cleared on
         * resubmit), so a SUBMITTED row with this set is a RESUBMISSION — the
         * only thing that tells a triaging reviewer it is not a first look.
         */
        Instant changesRequestedAt,
        long openCommentCount,
        Instant createdAt
) {}
