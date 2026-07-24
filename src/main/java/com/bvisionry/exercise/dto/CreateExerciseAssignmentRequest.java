package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Exercise assignment create request — the exercise mirror of
 * {@link com.bvisionry.assessment.dto.CreateAssignmentRequest}. The assigner
 * is always derived from the authenticated principal.
 */
public record CreateExerciseAssignmentRequest(
        @NotNull(message = "Template ID is required")
        UUID templateId,

        // Null or empty means "all members" (optionally narrowed by userType).
        List<UUID> memberIds,

        Instant deadline,

        @Size(max = 64, message = "Member type code must be 64 characters or less")
        String userType,

        // When true, creates the org-level provision only (super-admin only).
        // Boxed so an omitted JSON property coalesces to false.
        Boolean assignToOrganization
) {
    public CreateExerciseAssignmentRequest {
        assignToOrganization = assignToOrganization != null && assignToOrganization;
    }

    public boolean isAssignAll() {
        return memberIds == null || memberIds.isEmpty();
    }
}
