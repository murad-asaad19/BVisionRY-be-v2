package com.bvisionry.organization;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgScope;
import com.bvisionry.common.web.ProblemDetailResponseWriter;
import com.bvisionry.common.web.RequestPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
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

    // The interceptor must see every path the @PathVariable binder can resolve to
    // an org id, so the admission test IS the binder's: take the first segment as-is
    // and parse it with the same UUID.fromString the binder uses. Earlier attempts
    // to pre-filter with a shape regex were strictly narrower than UUID.fromString
    // (which also accepts short-group forms like "0-0-0-0-1" and truncates
    // over-long groups in 36-char spellings), so a non-canonical spelling reached
    // the handler with a REAL org id while this tenancy check silently abstained.
    // A segment UUID.fromString rejects (e.g. 36 dashes) abstains here and is
    // answered 400 by the binder's MethodArgumentTypeMismatchException mapping —
    // never a 500 out of this interceptor.
    private static final Pattern ORG_PATH_PATTERN = Pattern.compile(
            "^/api/organizations/([^/]+)(/.*)?$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) throws Exception {
        // Decoded like MVC matches it — a percent-encoded spelling of an org path
        // must not skip the tenancy check.
        String path = RequestPaths.decoded(request);
        Matcher matcher = ORG_PATH_PATTERN.matcher(path);

        if (!matcher.matches()) return true; // Not an org-scoped request

        UUID requestedOrgId;
        try {
            requestedOrgId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException notAnOrgId) {
            return true; // Not resolvable to an org id — the binder answers 400
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return true; // Let Spring Security handle unauthenticated
        }

        // Single getRole() call site: the ArchUnit ratchet freezes
        // cross-feature edges per origin method + target, and a second
        // occurrence of the same call would register as a new violation.
        UserRole role = user.getRole();
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
            ProblemDetailResponseWriter.write(response, HttpStatus.FORBIDDEN,
                    "Access denied: you do not belong to this organization");
            return false;
        }

        return true;
    }
}
