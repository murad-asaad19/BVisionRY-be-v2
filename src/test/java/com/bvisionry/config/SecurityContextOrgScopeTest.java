package com.bvisionry.config;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgHierarchyPort;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * The ONE tenancy predicate ({@code OrgScope}), formerly {@code OrgAccessGuard}'s
 * static: a parent org's ORG_ADMIN traverses into its sub-orgs; every other
 * cross-org combination stays denied. Plain unit test — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class SecurityContextOrgScopeTest {

    private final UUID parentOrgId = UUID.randomUUID();
    private final UUID childOrgId = UUID.randomUUID();
    private final UUID siblingOrgId = UUID.randomUUID();

    @Mock ObjectProvider<OrgHierarchyPort> orgHierarchyProvider;

    private SecurityContextOrgScope scope() {
        // The only parent->child edge in this fixture.
        lenient().when(orgHierarchyProvider.getIfAvailable()).thenReturn(new OrgHierarchyPort() {
            @Override
            public boolean isParentOf(UUID parent, UUID child) {
                return parentOrgId.equals(parent) && childOrgId.equals(child);
            }

            @Override
            public com.bvisionry.common.enums.SubscriptionTier effectiveTierOf(UUID orgId) {
                throw new UnsupportedOperationException("not used by the predicate");
            }
        });
        return new SecurityContextOrgScope(orgHierarchyProvider);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdmin_anyOrg_allowed() {
        authenticate(UserRole.SUPER_ADMIN, null);
        assertThat(scope().mayAccess(childOrgId)).isTrue();
        assertThat(scope().mayAccess(siblingOrgId)).isTrue();
    }

    @Test
    void orgAdmin_ownOrg_allowed() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(scope().mayAccess(parentOrgId)).isTrue();
    }

    @Test
    void parentOrgAdmin_subOrg_allowed() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(scope().mayAccess(childOrgId)).isTrue();
    }

    @Test
    void childOrgAdmin_parentOrg_denied() {
        authenticate(UserRole.ORG_ADMIN, childOrgId);
        assertThat(scope().mayAccess(parentOrgId)).isFalse();
    }

    @Test
    void orgAdmin_siblingOrg_denied() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(scope().mayAccess(siblingOrgId)).isFalse();
    }

    @Test
    void memberOfParent_subOrg_denied() {
        // Traversal is an ORG_ADMIN privilege — a plain MEMBER (or COACH /
        // INSTRUCTOR) of the parent gets no implicit sub-org access.
        authenticate(UserRole.MEMBER, parentOrgId);
        assertThat(scope().mayAccess(childOrgId)).isFalse();
    }

    @Test
    void unauthenticated_denied() {
        SecurityContextHolder.clearContext();
        assertThat(scope().mayAccess(parentOrgId)).isFalse();
    }

    @Test
    void nullOrgId_denied_andRequireThrows() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(scope().mayAccess(null)).isFalse();
        assertThatThrownBy(() -> scope().require(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void require_throwsAUniform403OnForeignOrg() {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThatThrownBy(() -> scope().require(siblingOrgId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Cross-org access denied");
    }

    /** Without the port (test slices without config beans) the predicate degrades to equality — no NPE. */
    @Test
    void missingHierarchyPort_fallsBackToOwnOrgEquality() {
        lenient().when(orgHierarchyProvider.getIfAvailable()).thenReturn(null);
        SecurityContextOrgScope degraded = new SecurityContextOrgScope(orgHierarchyProvider);
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(degraded.mayAccess(parentOrgId)).isTrue();
        assertThat(degraded.mayAccess(childOrgId)).isFalse();
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
