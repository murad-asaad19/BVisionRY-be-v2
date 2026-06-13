package com.bvisionry.auth;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        throw new AccessDeniedException("Not authenticated");
    }

    public static UUID getCurrentUserId() {
        return getCurrentUser().getId();
    }

    public static boolean isSuperAdmin() {
        return getCurrentUser().getRole() == UserRole.SUPER_ADMIN;
    }

    /**
     * Imperative variant of {@link OrgAccessGuard#isInOrg(UUID)} for service-layer
     * call sites that aren't gated by {@code @PreAuthorize}. Throws a uniform
     * {@link AccessDeniedException} so 403/404 don't leak the existence of a foreign org.
     */
    public static void requireOrgAccess(UUID orgId) {
        if (orgId == null) {
            throw new AccessDeniedException("Organization id is required");
        }
        if (!OrgAccessGuard.callerHasAccess(orgId)) {
            throw new AccessDeniedException("Cross-org access denied");
        }
    }
}
