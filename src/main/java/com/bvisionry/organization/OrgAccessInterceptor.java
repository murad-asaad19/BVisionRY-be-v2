package com.bvisionry.organization;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgHierarchyPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts all /api/organizations/{orgId}/** requests and verifies
 * the current user belongs to that organization (or is Super Admin, or is
 * an ORG_ADMIN of the requested org's parent — parent admins manage their
 * sub-organizations through the same org-scoped endpoints).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrgAccessInterceptor implements HandlerInterceptor {

    /**
     * Lazily resolved: this interceptor is instantiated in {@code @WebMvcTest}
     * slices (HandlerInterceptors are part of the web slice) where the
     * {@code OrgHierarchyAdapter} @Component is not — a hard constructor
     * dependency would fail every slice context. When absent, the traversal
     * check degrades to plain same-org equality (mirrors OrgAccessGuard's
     * null-safe static fallback).
     */
    private final ObjectProvider<OrgHierarchyPort> orgHierarchy;

    // Case-insensitive: UUIDs may arrive upper- or mixed-case in the path. A
    // lowercase-only class let an uppercase-UUID request slip past the membership
    // check entirely (the path no longer matched, so preHandle returned true).
    private static final Pattern ORG_PATH_PATTERN =
            Pattern.compile("^/api/organizations/([A-Fa-f0-9-]{36})(/.*)?$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) throws Exception {
        String path = request.getRequestURI();
        Matcher matcher = ORG_PATH_PATTERN.matcher(path);

        if (!matcher.matches()) return true; // Not an org-scoped request

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return true; // Let Spring Security handle unauthenticated
        }

        // Single getRole() call site: the ArchUnit ratchet freezes
        // cross-feature edges per origin method + target, and a second
        // occurrence of the same call would register as a new violation.
        UserRole role = user.getRole();

        // Super admin bypasses org isolation
        if (role == UserRole.SUPER_ADMIN) return true;

        UUID requestedOrgId = UUID.fromString(matcher.group(1));
        UUID userOrgId = user.getOrganization() != null ? user.getOrganization().getId() : null;

        // A parent org's ORG_ADMIN manages its sub-orgs through the same
        // org-scoped endpoints — mirror OrgAccessGuard.callerHasAccess.
        OrgHierarchyPort hierarchy = orgHierarchy.getIfAvailable();
        boolean parentAdminOfRequestedOrg = userOrgId != null
                && role == UserRole.ORG_ADMIN
                && hierarchy != null
                && hierarchy.isParentOf(userOrgId, requestedOrgId);

        if ((userOrgId == null || !userOrgId.equals(requestedOrgId)) && !parentAdminOfRequestedOrg) {
            log.warn("Org access denied: user {} (org {}) tried to access org {}",
                    user.getId(), userOrgId, requestedOrgId);
            response.setContentType("application/json");
            response.setStatus(403);
            response.getWriter().write("{\"status\":403,\"message\":\"Access denied: you do not belong to this organization\"}");
            return false;
        }

        return true;
    }
}
