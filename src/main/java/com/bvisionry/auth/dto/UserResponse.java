package com.bvisionry.auth.dto;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id, String email, String name, UserRole role, String userType, UserStatus status,
        String ssoProvider, String avatarUrl, UUID organizationId,
        /** Subscription tier of the user's organization, so the frontend can gate
         *  premium UI without a separate org-detail call. Null for users with no org. */
        SubscriptionTier organizationTier,
        Instant createdAt, Instant updatedAt
) {
    public static UserResponse from(User user) {
        SubscriptionTier tier = user.getOrganization() != null
                ? user.getOrganization().getSubscriptionTier()
                : null;
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getUserType(), user.getStatus(), user.getSsoProvider(), user.getAvatarUrl(),
                user.getOrganization() != null ? user.getOrganization().getId() : null,
                tier,
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
