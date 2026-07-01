package com.bvisionry.assessment.dto;

import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One assignment row — either an org-level provision ({@code userId} null) or a
 * per-member assignment. Status + submissionId come from the member's latest
 * submission when {@code userId} is set.
 *
 * {@code pipelineStatus} lets the UI flag a provision whose pipeline was later
 * archived (still listed, but not assignable to members).
 */
public record AssignmentResponse(
        UUID id,
        UUID pipelineId,
        String pipelineName,
        PipelineStatus pipelineStatus,
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
