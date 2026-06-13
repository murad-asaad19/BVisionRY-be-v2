package com.bvisionry.organization.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregated attempt counters for a single invitation. Built by a grouped
 * JPQL query so the org-scoped listing can render "had failed attempts"
 * indicators without N+1ing the attempts table. The most-recent error
 * message is loaded separately by the detail endpoint when an admin clicks
 * through — keeping it out of the bulk aggregate avoids a window function /
 * vendor-specific DISTINCT ON in JPQL.
 */
public record InvitationAttemptSummary(
        UUID invitationId,
        long attemptCount,
        long failedAttemptCount,
        Instant lastAttemptAt
) {
    public static InvitationAttemptSummary empty(UUID invitationId) {
        return new InvitationAttemptSummary(invitationId, 0L, 0L, null);
    }
}
