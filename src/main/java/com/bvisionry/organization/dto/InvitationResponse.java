package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.UserRole;
import com.bvisionry.organization.entity.Invitation;

import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(
        UUID id, String email, String organizationName, UserRole role, String status,
        UUID token, Instant expiresAt, Instant acceptedAt, Instant createdAt,
        Instant firstViewedAt, Instant lastViewedAt, int viewCount,
        long attemptCount, long failedAttemptCount, Instant lastAttemptAt
) {
    /**
     * Used by the public token-fetch endpoint and any path that doesn't have
     * an attempt summary handy. Attempt counters default to zero — admins
     * fetching the org-scoped listing get the enriched form below.
     */
    public static InvitationResponse from(Invitation inv) {
        return from(inv, InvitationAttemptSummary.empty(inv.getId()));
    }

    public static InvitationResponse from(Invitation inv, InvitationAttemptSummary summary) {
        return new InvitationResponse(
                inv.getId(), inv.getEmail(), inv.getOrganization().getName(), inv.getRole(), inv.getStatus().name(),
                inv.getToken(), inv.getExpiresAt(), inv.getAcceptedAt(), inv.getCreatedAt(),
                inv.getFirstViewedAt(), inv.getLastViewedAt(), inv.getViewCount(),
                summary.attemptCount(), summary.failedAttemptCount(), summary.lastAttemptAt());
    }
}
