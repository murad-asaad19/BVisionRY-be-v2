package com.bvisionry.organization;

import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.OrgHierarchyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link OrgHierarchyPort} backed by the {@code organizations} table. Kept in
 * the organization package so the shared kernel stays free of feature imports.
 */
@Component
@RequiredArgsConstructor
public class OrgHierarchyAdapter implements OrgHierarchyPort {

    private final OrganizationRepository organizationRepository;

    @Override
    public boolean isParentOf(UUID parentOrgId, UUID childOrgId) {
        if (parentOrgId == null || childOrgId == null) return false;
        return organizationRepository.existsByIdAndParentOrganizationId(childOrgId, parentOrgId);
    }

    @Override
    public SubscriptionTier effectiveTierOf(UUID orgId) {
        // Fetch-join the parent: OSIV is off and callers may have no open
        // session, so the lazy parent must be initialized by the query itself.
        return organizationRepository.findWithParentById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()))
                .effectiveSubscriptionTier();
    }
}
