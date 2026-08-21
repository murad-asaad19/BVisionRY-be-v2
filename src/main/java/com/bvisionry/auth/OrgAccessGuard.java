package com.bvisionry.auth;

import com.bvisionry.common.security.OrgScope;
import org.springframework.stereotype.Component;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

/**
 * SpEL bean used by {@code @PreAuthorize} on every {@code /api/organizations/{orgId}/*}
 * controller. Without this, role-only checks like {@code hasAnyAuthority('SUPER_ADMIN','ORG_ADMIN')}
 * would let an ORG_ADMIN of org A call admin endpoints under org B's path.
 *
 * <p>Usage:
 * <pre>
 *   &#064;PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and &#064;orgAccess.isInOrg(#orgId))")
 * </pre>
 *
 * <p>Returning a boolean (rather than throwing) keeps SpEL composition clean and lets
 * Spring Security raise a uniform {@code AccessDeniedException} → 403, so the global
 * handler can return a generic message that doesn't disclose whether the foreign org exists.
 *
 * <p>The predicate itself lives in ONE place — {@link OrgScope} — shared with the
 * org-path interceptor and every imperative service-layer check, so the tenancy
 * rule (own org, SUPER_ADMIN, or ORG_ADMIN of the org's parent) cannot fork per
 * mechanism.
 */
@Component("orgAccess")
@RequiredArgsConstructor
public class OrgAccessGuard {

    private final OrgScope orgScope;

    public boolean isInOrg(UUID orgId) {
        return orgScope.mayAccess(orgId);
    }
}
