package com.bvisionry.organization;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgScope;
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
     * {@code config} adapter @Component is not — a hard constructor dependency
     * would fail every slice context. When absent, the check degrades to
     * SUPER_ADMIN-or-same-org equality (the pre-hierarchy predicate).
     */
    private final ObjectProvider<OrgScope> orgScope;

    // Case-insensitive: UUIDs may arrive upper- or mixed-case in the path. A
    // lowercase-only class let an uppercase-UUID request slip past the membership
    // check entirely (the path no longer matched, so preHandle returned true).
    //
    // Canonical 8-4-4-4-12 SHAPE, not a 36-character COUNT. The previous
    // `[A-Fa-f0-9-]{36}` matched 36 dashes, which then threw IllegalArgumentException
    // out of UUID.fromString below — GlobalExceptionHandler has no
    // IllegalArgumentException handler, so a malformed path answered 500 instead of
    // 400. Not matching hands the segment to Spring's @PathVariable binder, whose
    // MethodArgumentTypeMismatchException is mapped to 400.
    //
    // Deliberately still NARROWER than UUID.fromString, which also accepts short
    // group forms such as "0-0-0-0-1". Those skip this interceptor and are left to
    // the endpoint's own @PreAuthorize — OrganizationBrandingIntegrationTest uses
    // exactly that path to falsify its predicates.
    private static final Pattern ORG_PATH_PATTERN = Pattern.compile(
            "^/api/organizations/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

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
        UUID requestedOrgId = UUID.fromString(matcher.group(1));
        UUID userOrgId = user.getOrganization() != null ? user.getOrganization().getId() : null;

        // ONE tenancy predicate, owned by OrgScope — the same rule the
        // @orgAccess SpEL bean and the service-layer checks ask. This
        // interceptor is defence-in-depth over @PreAuthorize, not a second rule.
        OrgScope scope = orgScope.getIfAvailable();
        boolean allowed = scope != null
                ? scope.mayAccess(requestedOrgId)
                // Sliced-context fallback (no config beans): the pre-hierarchy
                // predicate, so @WebMvcTest behaviour matches the old inline check.
                : role == UserRole.SUPER_ADMIN
                        || (userOrgId != null && userOrgId.equals(requestedOrgId));

        if (!allowed) {
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
