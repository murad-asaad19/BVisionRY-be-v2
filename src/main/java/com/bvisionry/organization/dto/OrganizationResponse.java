package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.DisplayState;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.organization.entity.Organization;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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
        Instant createdAt,
        Instant updatedAt
) {
    private static final int IDLE_DAYS = 14;

    public static OrganizationResponse from(Organization org, long memberCount, Instant lastActiveAt) {
        DisplayState ds = computeDisplayState(org, lastActiveAt);
        return new OrganizationResponse(
                org.getId(), org.getName(), org.getDescription(),
                org.getSubscriptionTier(), org.isActive(),
                org.getTrialEndsAt(), lastActiveAt, ds,
                memberCount,
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
