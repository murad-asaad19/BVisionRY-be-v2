package com.bvisionry.auth;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgHierarchyPort;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hierarchy-aware tenancy predicate: a parent org's ORG_ADMIN traverses into
 * its sub-orgs; every other cross-org combination stays denied. Plain unit
 * test — no Spring context — so the static {@code orgHierarchy} is stubbed
 * directly through the constructor and restored afterwards to avoid leaking
 * into a cached Spring test context in the same JVM.
 */
class OrgAccessGuardTest {

    private final UUID parentOrgId = UUID.randomUUID();
    private final UUID childOrgId = UUID.randomUUID();
    private final UUID siblingOrgId = UUID.randomUUID();

    private OrgHierarchyPort savedPort;

    @BeforeEach
    void stubHierarchy() {
        savedPort = OrgAccessGuard.orgHierarchy;
        // The only parent->child edge in this fixture.
        new OrgAccessGuard(new OrgHierarchyPort() {
            @Override
            public boolean isParentOf(UUID parent, UUID child) {
                return parentOrgId.equals(parent) && childOrgId.equals(child);
            }

            @Override
            public com.bvisionry.common.enums.SubscriptionTier effectiveTierOf(UUID orgId) {
                throw new UnsupportedOperationException("not used by the guard");
            }
        });
    }

    @AfterEach
    void restore() {
        OrgAccessGuard.orgHierarchy = savedPort;
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdmin_anyOrg_allowed() {
        authenticate(UserRole.SUPER_ADMIN, null);
        assertThat(OrgAccessGuard.callerHasAccess(childOrgId)).isTrue();
        assertThat(OrgAccessGuard.callerHasAccess(siblingOrgId)).isTrue();
    }

    @Test
    void orgAdmin_ownOrg_allowed() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(OrgAccessGuard.callerHasAccess(parentOrgId)).isTrue();
    }

    @Test
    void parentOrgAdmin_subOrg_allowed() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(OrgAccessGuard.callerHasAccess(childOrgId)).isTrue();
    }

    @Test
    void childOrgAdmin_parentOrg_denied() {
        authenticate(UserRole.ORG_ADMIN, childOrgId);
        assertThat(OrgAccessGuard.callerHasAccess(parentOrgId)).isFalse();
    }

    @Test
    void orgAdmin_siblingOrg_denied() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(OrgAccessGuard.callerHasAccess(siblingOrgId)).isFalse();
    }

    @Test
    void memberOfParent_subOrg_denied() {
        // Traversal is an ORG_ADMIN privilege — a plain MEMBER (or MANAGER /
        // INSTRUCTOR) of the parent gets no implicit sub-org access.
        authenticate(UserRole.MEMBER, parentOrgId);
        assertThat(OrgAccessGuard.callerHasAccess(childOrgId)).isFalse();
    }

    @Test
    void unauthenticated_denied() {
        SecurityContextHolder.clearContext();
        assertThat(OrgAccessGuard.callerHasAccess(parentOrgId)).isFalse();
    }

    @Test
    void nullOrgId_denied() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(OrgAccessGuard.callerHasAccess(null)).isFalse();
    }

    /** Without the port (plain unit tests, no Spring) the predicate degrades to equality — no NPE. */
    @Test
    void missingHierarchyPort_fallsBackToOwnOrgEquality() {
        OrgAccessGuard.orgHierarchy = null;
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(OrgAccessGuard.callerHasAccess(parentOrgId)).isTrue();
        assertThat(OrgAccessGuard.callerHasAccess(childOrgId)).isFalse();
    }

    private void authenticate(UserRole role, UUID orgId) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setRole(role);
        if (orgId != null) {
            Organization org = new Organization();
            org.setId(orgId);
            user.setOrganization(org);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority(role.name()))));
    }
}
