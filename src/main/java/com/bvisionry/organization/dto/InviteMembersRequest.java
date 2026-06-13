package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record InviteMembersRequest(
        @NotEmpty(message = "At least one email is required")
        List<@Email(message = "Invalid email format") String> emails,
        UserRole role,
        UUID invitedBy
) {
    public InviteMembersRequest {
        if (role == null) role = UserRole.MEMBER;
    }
}
