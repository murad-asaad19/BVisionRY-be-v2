package com.bvisionry.assessment.dto;

import com.bvisionry.assessment.entity.PipelineAutoAssignment;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for an auto-assign rule. {@code userType == null} means the
 * rule applies to every member in the organization regardless of type.
 */
public record AutoAssignmentResponse(
        UUID id,
        UUID organizationId,
        UUID pipelineId,
        String pipelineName,
        String userType,
        Instant deadline,
        UUID createdBy,
        Instant createdAt,
        int maxCheckIns
) {
    public static AutoAssignmentResponse from(PipelineAutoAssignment rule) {
        return new AutoAssignmentResponse(
                rule.getId(),
                rule.getOrganization().getId(),
                rule.getPipeline().getId(),
                rule.getPipeline().getName(),
                rule.getUserType(),
                rule.getDeadline(),
                rule.getCreatedBy(),
                rule.getCreatedAt(),
                rule.getMaxCheckIns()
        );
    }
}
