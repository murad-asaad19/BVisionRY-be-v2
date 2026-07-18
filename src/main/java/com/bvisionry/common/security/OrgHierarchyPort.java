package com.bvisionry.common.security;

import com.bvisionry.common.enums.SubscriptionTier;

import java.util.UUID;

/**
 * Shared-kernel view of the organization hierarchy, consumed by tenancy
 * guards (e.g. {@code OrgAccessGuard} in {@code auth}) that must not depend
 * on the {@code organization} feature package directly (ArchUnit shared-kernel
 * rule: features may depend on {@code common}, never the reverse — hence the
 * UUID-only signature).
 *
 * <p>Implemented in the {@code organization} package against the
 * {@code organizations.parent_organization_id} column.
 */
public interface OrgHierarchyPort {

    /**
     * True iff {@code childOrgId} is a direct sub-organization of
     * {@code parentOrgId}. The hierarchy is one level deep, so "direct"
     * equals "any ancestor".
     */
    boolean isParentOf(UUID parentOrgId, UUID childOrgId);

    /**
     * The tier that governs feature access for {@code orgId}: sub-orgs
     * inherit the parent's plan; root orgs use their own. Consumed by
     * {@code reporting} (PremiumFeatureGuard) which, like {@code auth},
     * must not grow new dependency edges on the organization package.
     *
     * @throws com.bvisionry.common.exception.ResourceNotFoundException if the org doesn't exist
     */
    SubscriptionTier effectiveTierOf(UUID orgId);
}
