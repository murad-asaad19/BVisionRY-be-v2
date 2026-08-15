package com.bvisionry.config;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgHierarchyPort;
import com.bvisionry.common.security.OrgScope;

import lombok.RequiredArgsConstructor;

/**
 * {@link OrgScope} adapter over the Spring security context. Lives in
 * {@code config} (shared wiring, allowed to cross feature lines) like
 * {@link SecurityContextCurrentUserAccessor}.
 */
@Component
@RequiredArgsConstructor
public class SecurityContextOrgScope implements OrgScope {

    /**
     * Lazily resolved so test slices without the {@code organization} package's
     * adapter still get a working scope — the hierarchy arm simply degrades to
     * same-org equality, mirroring the guards this class replaced.
     */
    private final ObjectProvider<OrgHierarchyPort> orgHierarchy;

    @Override
    public boolean mayAccess(UUID orgId) {
        if (orgId == null) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User caller)) {
            return false;
        }
        if (caller.getRole() == UserRole.SUPER_ADMIN) {
            return true;
        }
        if (caller.getOrganization() == null) {
            return false;
        }
        UUID callerOrgId = caller.getOrganization().getId();
        if (orgId.equals(callerOrgId)) {
            return true;
        }
        OrgHierarchyPort hierarchy = orgHierarchy.getIfAvailable();
        return caller.getRole() == UserRole.ORG_ADMIN
                && hierarchy != null
                && hierarchy.isParentOf(callerOrgId, orgId);
    }

    @Override
    public void require(UUID orgId) {
        if (orgId == null) {
            throw new AccessDeniedException("Organization id is required");
        }
        if (!mayAccess(orgId)) {
            throw new AccessDeniedException("Cross-org access denied");
        }
    }
}
