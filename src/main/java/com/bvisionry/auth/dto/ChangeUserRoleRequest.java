package com.bvisionry.auth.dto;

import com.bvisionry.common.enums.UserRole;
import jakarta.validation.constraints.NotNull;

/**
 * Platform-level role change for a single user, issued from the super-admin
 * "Platform Admins" view. Unlike {@code ChangeMemberRoleRequest} (org-scoped,
 * which forbids SUPER_ADMIN), this path accepts SUPER_ADMIN because it is
 * mounted on the SUPER_ADMIN-only {@code /api/users} surface.
 */
public record ChangeUserRoleRequest(
        @NotNull(message = "Role is required")
        UserRole role
) {}
