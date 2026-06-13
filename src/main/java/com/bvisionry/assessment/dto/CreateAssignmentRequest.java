package com.bvisionry.assessment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Assignment create request. {@code assignedBy} is intentionally absent — the
 * server derives it from the authenticated principal via
 * {@link com.bvisionry.auth.SecurityUtils#getCurrentUserId()} so a client
 * can't ascribe the action to another user.
 */
public record CreateAssignmentRequest(
        @NotNull(message = "Pipeline ID is required")
        UUID pipelineId,

        // "all" or list of specific member IDs
        // If null or empty, defaults to "all"
        List<UUID> memberIds,

        Instant deadline,

        // Optional: filter members by member type code (e.g. "LEADER", "FOUNDER")
        @Size(max = 64, message = "Member type code must be 64 characters or less")
        String userType,

        /*
         * When true, persists an auto-assign rule keyed on (org, pipeline,
         * userType). Future joiners matching the userType (or anyone, when
         * userType is null) will receive this pipeline automatically. Only
         * valid in combination with {@link #isAssignAll()} — mixing explicit
         * memberIds with auto-assign is rejected by the service.
         */
        boolean autoAssignFutureMembers,

        /*
         * How many times the assigned member may complete the pipeline. Each
         * completion is a "check-in" — periodic progress measurement, not a
         * retry. {@code null} defaults to 1 (single check-in, historical
         * behavior). Must be >= 1.
         */
        @Min(value = 1, message = "maxCheckIns must be at least 1")
        Integer maxCheckIns
) {
    public int maxCheckInsOrDefault() {
        return maxCheckIns == null ? 1 : maxCheckIns;
    }
    public boolean isAssignAll() {
        return memberIds == null || memberIds.isEmpty();
    }
}
