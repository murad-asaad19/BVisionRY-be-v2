package com.bvisionry.auth;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
 */
@Component("orgAccess")
public class OrgAccessGuard {

    public boolean isInOrg(UUID orgId) {
        return callerHasAccess(orgId);
    }

    /**
     * Single source of truth for the tenancy predicate. Used both by this Spring bean
     * (for SpEL) and by {@link SecurityUtils#requireOrgAccess(UUID)} (for imperative
     * service-layer checks).
     */
    static boolean callerHasAccess(UUID orgId) {
        if (orgId == null) return false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User caller)) return false;
        if (caller.getRole() == UserRole.SUPER_ADMIN) return true;
        return caller.getOrganization() != null
                && orgId.equals(caller.getOrganization().getId());
    }
}
