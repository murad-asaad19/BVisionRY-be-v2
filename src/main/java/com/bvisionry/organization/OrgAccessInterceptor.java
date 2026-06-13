package com.bvisionry.organization;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts all /api/organizations/{orgId}/** requests and verifies
 * the current user belongs to that organization (or is Super Admin).
 */
@Component
@Slf4j
public class OrgAccessInterceptor implements HandlerInterceptor {

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

        // Super admin bypasses org isolation
        if (user.getRole() == UserRole.SUPER_ADMIN) return true;

        UUID requestedOrgId = UUID.fromString(matcher.group(1));
        UUID userOrgId = user.getOrganization() != null ? user.getOrganization().getId() : null;

        if (userOrgId == null || !userOrgId.equals(requestedOrgId)) {
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
