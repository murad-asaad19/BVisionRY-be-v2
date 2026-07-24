package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.DisplayState;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.organization.entity.Organization;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * API view of an organization.
 *
 * <p>{@code memberCount} counts DIRECT members only — a parent org's count
 * does not include members of its sub-organizations (each sub-org lists its
 * own). {@code subOrganizationCount} is always 0 for sub-orgs (the hierarchy
 * is one level deep). {@code effectiveSubscriptionTier} is the tier that
 * governs feature access: the parent's tier for sub-orgs, own tier otherwise.
 */
public record OrganizationResponse(
        UUID id,
        String name,
        String description,
        SubscriptionTier subscriptionTier,
        boolean active,
        Instant trialEndsAt,
        Instant lastActiveAt,
        DisplayState displayState,
        long memberCount,
        UUID parentOrganizationId,
        String parentOrganizationName,
        SubscriptionTier effectiveSubscriptionTier,
        int subOrganizationCount,
        Instant createdAt,
        Instant updatedAt
) {
    private static final int IDLE_DAYS = 14;

    /**
     * Reads the (lazy) parent for id/name/effective tier — call inside a
     * transaction or with the parent already fetched (OSIV is off).
     */
    public static OrganizationResponse from(Organization org, long memberCount, Instant lastActiveAt,
                                            int subOrganizationCount) {
        DisplayState ds = computeDisplayState(org, lastActiveAt);
        Organization parent = org.getParentOrganization();
        return new OrganizationResponse(
                org.getId(), org.getName(), org.getDescription(),
                org.getSubscriptionTier(), org.isActive(),
                org.getTrialEndsAt(), lastActiveAt, ds,
                memberCount,
                parent != null ? parent.getId() : null,
                parent != null ? parent.getName() : null,
                org.effectiveSubscriptionTier(),
                subOrganizationCount,
                org.getCreatedAt(), org.getUpdatedAt()
        );
    }

    private static DisplayState computeDisplayState(Organization org, Instant lastActiveAt) {
        if (!org.isActive()) return DisplayState.SUSPENDED;
        if (org.isOnTrial()) return DisplayState.TRIAL;
        Instant idleCutoff = Instant.now().minus(IDLE_DAYS, ChronoUnit.DAYS);
        if (lastActiveAt == null || lastActiveAt.isBefore(idleCutoff)) {
            // An org with no recorded login from any member counts as idle —
            // empty/onboarding orgs may need attention even if not "stale".
            return DisplayState.IDLE;
        }
        return DisplayState.ACTIVE;
    }
}
