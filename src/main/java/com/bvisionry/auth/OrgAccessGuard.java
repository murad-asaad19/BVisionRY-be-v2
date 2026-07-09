package com.bvisionry.auth;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgHierarchyPort;
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
 *
 * <p>Hierarchy: an ORG_ADMIN of a parent organization also has access to its
 * sub-organizations (one level deep), so every org-scoped surface — members,
 * invitations, dashboards, insights — works against a sub-org id with no
 * per-controller changes. Traversal is resolved through {@link OrgHierarchyPort}
 * (implemented in the organization package) so this class keeps depending only
 * on the shared kernel.
 */
@Component("orgAccess")
public class OrgAccessGuard {

    /**
     * Set from the Spring-managed singleton's constructor. Static because
     * {@link #callerHasAccess(UUID)} must stay static: it backs
     * {@link SecurityUtils#requireOrgAccess(UUID)}, which is called from ~15
     * static utility call sites (catalog/quiz) that have no bean access.
     * Package-private (not private) so tests in this package can save/restore it.
     */
    static OrgHierarchyPort orgHierarchy;

    public OrgAccessGuard(OrgHierarchyPort orgHierarchy) {
        OrgAccessGuard.orgHierarchy = orgHierarchy;
    }

    public boolean isInOrg(UUID orgId) {
        return callerHasAccess(orgId);
    }

    /**
     * Single source of truth for the tenancy predicate. Used both by this Spring bean
     * (for SpEL) and by {@link SecurityUtils#requireOrgAccess(UUID)} (for imperative
     * service-layer checks).
     *
     * <p>Grants access when the caller is SUPER_ADMIN, belongs to the target org, or
     * is an ORG_ADMIN of the target org's parent. When {@link #orgHierarchy} is unset
     * (plain unit tests without a Spring context), the predicate degrades to the
     * pre-hierarchy equality check instead of throwing NPE.
     */
    static boolean callerHasAccess(UUID orgId) {
        if (orgId == null) return false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User caller)) return false;
        if (caller.getRole() == UserRole.SUPER_ADMIN) return true;
        if (caller.getOrganization() == null) return false;
        UUID callerOrgId = caller.getOrganization().getId();
        if (orgId.equals(callerOrgId)) return true;
        return caller.getRole() == UserRole.ORG_ADMIN
                && orgHierarchy != null
                && orgHierarchy.isParentOf(callerOrgId, orgId);
    }
}
