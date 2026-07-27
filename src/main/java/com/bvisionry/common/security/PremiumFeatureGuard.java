package com.bvisionry.common.security;

import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.PremiumRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Optional;
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
     * The tier that governs {@code orgId}'s CAPACITY, or empty when the caller
     * bypasses entitlement entirely (SUPER_ADMIN).
     *
     * <p>Exists so a capacity meter that this class cannot own — the cohort
     * rate, which needs to count cohorts and therefore lives in
     * {@code programflow} — still resolves "effective tier" and "does this
     * caller bypass" through the SAME two collaborators as every other gate,
     * instead of forking the super-admin rule or the sub-org inheritance rule.
     * The shared kernel must not depend on a feature package, so the count
     * stays out there and only the verdict inputs come from here.
     *
     * <p>Empty means "no ceiling applies", not "FREE" — mixing those up is how
     * a bypass turns into a refusal.
     */
    public Optional<SubscriptionTier> governingTier(UUID orgId) {
        return isSuperAdmin() ? Optional.empty() : Optional.of(orgHierarchy.effectiveTierOf(orgId));
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
     * Returns true if the organization's EFFECTIVE subscription is a PAID one —
     * sub-organizations inherit the parent's plan, so a FREE sub-org under a
     * paying parent passes. Resolved through {@link OrgHierarchyPort} (which
     * throws ResourceNotFoundException for unknown orgs, preserving the
     * previous findById behaviour).
     *
     * <p>Was {@code == PREMIUM} before V156 split paid into Starter / Growth /
     * Founder Success. {@link SubscriptionTier#isPaid()} is the SAME question
     * that test always asked, so every gated surface behaves exactly as it did:
     * ex-PREMIUM orgs are GROWTH and still pass, FREE orgs still fail. The name
     * stays "premium" because the whole vocabulary around it does —
     * {@code PremiumRequiredException}, {@code checkPremium}, the 403 body — and
     * renaming that is churn, not a behaviour change.
     */
    public boolean isPremium(UUID orgId) {
        return orgHierarchy.effectiveTierOf(orgId).isPaid();
    }

    /**
     * Returns true if premium features should be available.
     * Super Admin always gets premium features regardless of org tier.
     */
    public boolean isPremiumOrSuperAdmin(UUID orgId) {
        return isSuperAdmin() || isPremium(orgId);
    }
}
