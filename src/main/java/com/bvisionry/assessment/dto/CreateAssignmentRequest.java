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
         * When true, creates an org-level provision only (no member rows,
         * no submissions). Super-admin only — org admins distribute to members
         * via the normal member assignment flow once a provision exists.
         *
         * Boxed (not primitive) so an OMITTED flag deserializes cleanly: Jackson
         * cannot map an absent JSON property onto a record's primitive component
         * (it would pass null to the canonical constructor and trip
         * FAIL_ON_NULL_FOR_PRIMITIVES). The compact constructor below coalesces
         * null → false, so callers still read a non-null boolean.
         */
        Boolean assignToOrganization,

        /*
         * When true, persists an auto-assign rule keyed on (org, pipeline,
         * userType). Future joiners matching the userType (or anyone, when
         * userType is null) will receive this pipeline automatically. Only
         * valid in combination with {@link #isAssignAll()} — mixing explicit
         * memberIds with auto-assign is rejected by the service.
         *
         * Boxed for the same reason as {@link #assignToOrganization} — an
         * omitted flag must default to false rather than 400 the request.
         */
        Boolean autoAssignFutureMembers,

        /*
         * How many times the assigned member may complete the pipeline. Each
         * completion is a "check-in" — periodic progress measurement, not a
         * retry. {@code null} defaults to 1 (single check-in, historical
         * behavior). Must be >= 1.
         */
        @Min(value = 1, message = "maxCheckIns must be at least 1")
        Integer maxCheckIns
) {
    /**
     * Normalize optional flags so an omitted (null) JSON property defaults to
     * false. This keeps the accessors non-null and lets every call site treat
     * them as a plain boolean without NPE risk.
     */
    public CreateAssignmentRequest {
        assignToOrganization = assignToOrganization != null && assignToOrganization;
        autoAssignFutureMembers = autoAssignFutureMembers != null && autoAssignFutureMembers;
    }

    public int maxCheckInsOrDefault() {
        return maxCheckIns == null ? 1 : maxCheckIns;
    }
    public boolean isAssignAll() {
        return memberIds == null || memberIds.isEmpty();
    }
}
