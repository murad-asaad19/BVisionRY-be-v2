package com.bvisionry.common.security;

import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.PremiumRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Entitlement gate: "is this organization paying for this feature at all".
 * Orthogonal to tenancy and RBAC, which answer "may this caller read THIS
 * org's data" — a FREE org's own admin clears both and is still refused here.
 *
 * <p>Lives in the shared kernel because entitlement is not any one feature's
 * concern: {@code reporting} and {@code insights} both gate on it, and
 * {@link OrgHierarchyPort}'s own javadoc records that this guard "must not grow
 * new dependency edges on the organization package". Homing it in a feature
 * package meant every additional gated surface had to import across a feature
 * line; from {@code common} they simply depend on the kernel, as the ArchUnit
 * module rules intend.
 *
 * <p>Both of its collaborators are existing shared-kernel ports for that reason:
 * {@link OrgHierarchyPort} for the effective tier, {@link CurrentUserAccessor}
 * for the super-admin bypass.
 */
@Service
@RequiredArgsConstructor
public class PremiumFeatureGuard {

    private final OrgHierarchyPort orgHierarchy;
    private final CurrentUserAccessor currentUser;

    /**
     * Checks if the organization has a Premium subscription.
     * Super Admin bypasses this check entirely.
     * Throws PremiumRequiredException (403) if the org is on the Free tier.
     */
    public void checkPremium(UUID orgId, String feature) {
        if (isSuperAdmin()) return;
        if (!isPremium(orgId)) {
            throw new PremiumRequiredException(feature);
        }
    }

    /**
     * The ORIGINAL principal-based check, preserved exactly through
     * {@link CurrentUserAccessor} (whose adapter reads
     * {@code SecurityUtils.getCurrentUser().getRole()}).
     *
     * <p>Deliberately NOT a granted-authority test: several call sites
     * authenticate a principal with an EMPTY authority list, so an
     * authority-based check would silently answer differently for a caller
     * whose role IS SUPER_ADMIN. Pinned by
     * {@code superAdminPrincipalWithNoGrantedAuthorities_stillBypasses}.
     *
     * <p>Unauthenticated reads as "not super admin" rather than propagating —
     * the guard runs on paths where nothing is authenticated, and throwing here
     * would surface as a 500 instead of the intended 403 gate.
     */
    private boolean isSuperAdmin() {
        try {
            return UserRole.SUPER_ADMIN.name().equals(currentUser.require().role());
        } catch (AccessDeniedException notAuthenticated) {
            return false;
        }
    }

    /**
     * Returns true if the organization's EFFECTIVE subscription is Premium —
     * sub-organizations inherit the parent's plan, so a FREE sub-org under a
     * PREMIUM parent passes. Resolved through {@link OrgHierarchyPort} (which
     * throws ResourceNotFoundException for unknown orgs, preserving the
     * previous findById behaviour).
     */
    public boolean isPremium(UUID orgId) {
        return orgHierarchy.effectiveTierOf(orgId) == SubscriptionTier.PREMIUM;
    }

    /**
     * Returns true if premium features should be available.
     * Super Admin always gets premium features regardless of org tier.
     */
    public boolean isPremiumOrSuperAdmin(UUID orgId) {
        return isSuperAdmin() || isPremium(orgId);
    }
}
