package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.UserRole;
import jakarta.validation.constraints.NotNull;

public record ChangeMemberRoleRequest(
        @NotNull(message = "Role is required")
        UserRole role
) {}
