package com.bvisionry.assessment.dto;

import com.bvisionry.assessment.entity.PipelineAutoAssignment;
import com.bvisionry.common.enums.UserRole;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read model for an auto-assign rule. {@code userType == null} means the
 * rule applies to every member TYPE in the organization; {@code targetRoles}
 * separately bounds which ROLES it measures at all (V158).
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
        int maxCheckIns,
        /**
         * Roles this rule fires for. A LIST rather than a set so the wire order
         * is stable — an unordered JSON array makes the console's rule summary
         * reshuffle between reads for no reason.
         */
        List<UserRole> targetRoles
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
                rule.getMaxCheckIns(),
                sortedRoles(rule.getTargetRoles())
        );
    }

    /** Declaration order (SUPER_ADMIN → MEMBER), so the list reads the same every time. */
    private static List<UserRole> sortedRoles(Set<UserRole> roles) {
        return roles == null ? List.of()
                : roles.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
    }
}
