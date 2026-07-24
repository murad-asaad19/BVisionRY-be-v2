package com.bvisionry.organization;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgHierarchyPort;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Hand-rolled 403 layer of the tenancy defence: same traversal rule as
 * {@code OrgAccessGuard} — a parent org's ORG_ADMIN may enter its sub-orgs'
 * paths; everyone else stays fenced to their own org.
 */
@ExtendWith(MockitoExtension.class)
class OrgAccessInterceptorTest {

    @Mock OrgHierarchyPort orgHierarchy;
    @Mock ObjectProvider<OrgHierarchyPort> orgHierarchyProvider;
    OrgAccessInterceptor interceptor;

    private final UUID parentOrgId = UUID.randomUUID();
    private final UUID childOrgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Not every test path resolves the provider (super-admin short-circuits).
        lenient().when(orgHierarchyProvider.getIfAvailable()).thenReturn(orgHierarchy);
        interceptor = new OrgAccessInterceptor(orgHierarchyProvider);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownOrg_allowed() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(orgRequest(parentOrgId), response, new Object())).isTrue();
    }

    @Test
    void parentOrgAdmin_subOrgPath_allowed() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        when(orgHierarchy.isParentOf(parentOrgId, childOrgId)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(orgRequest(childOrgId), response, new Object())).isTrue();
    }

    @Test
    void childOrgAdmin_parentPath_403() throws Exception {
        authenticate(UserRole.ORG_ADMIN, childOrgId);
        when(orgHierarchy.isParentOf(childOrgId, parentOrgId)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(orgRequest(parentOrgId), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void memberOfParent_subOrgPath_403() throws Exception {
        // Traversal requires ORG_ADMIN — the hierarchy port must not even be
        // consulted for a MEMBER (no stubbing here; strict stubs would flag it).
        authenticate(UserRole.MEMBER, parentOrgId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(orgRequest(childOrgId), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void superAdmin_anyOrgPath_allowed() throws Exception {
        authenticate(UserRole.SUPER_ADMIN, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(orgRequest(childOrgId), response, new Object())).isTrue();
    }

    @Test
    void nonOrgPath_ignored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/organizations");
        request.setRequestURI("/api/organizations");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    private MockHttpServletRequest orgRequest(UUID orgId) {
        String uri = "/api/organizations/" + orgId + "/members";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
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
