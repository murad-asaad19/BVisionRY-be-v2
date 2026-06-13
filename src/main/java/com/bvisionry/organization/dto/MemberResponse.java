package com.bvisionry.organization.dto;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID id, String email, String name, UserRole role, String userType, UserStatus status,
        Instant invitedAt, Instant activatedAt, Instant createdAt
) {
    public static MemberResponse from(User user) {
        return new MemberResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getUserType(), user.getStatus(), user.getInvitedAt(), user.getActivatedAt(), user.getCreatedAt());
    }
}
