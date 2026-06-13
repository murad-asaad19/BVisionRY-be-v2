package com.bvisionry.assessment.dto;

import com.bvisionry.common.enums.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One pipeline assigned to one member (there are no aggregate per-org assignments
 * any more). Status + submissionId come from the single matching submission.
 */
public record AssignmentResponse(
        UUID id,
        UUID pipelineId,
        String pipelineName,
        UUID organizationId,
        UUID userId,
        String userName,
        String userEmail,
        UUID assignedBy,
        Instant deadline,
        UUID submissionId,
        SubmissionStatus status,
        Instant createdAt,
        int maxCheckIns,
        int checkInCount
) {}
