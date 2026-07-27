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

    @Test
    void mixedCaseUuid_stillGuarded() throws Exception {
        authenticate(UserRole.ORG_ADMIN, childOrgId);
        when(orgHierarchy.isParentOf(childOrgId, parentOrgId)).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(pathRequest(
                "/api/organizations/" + parentOrgId.toString().toUpperCase() + "/members"),
                response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    /**
     * OVER-MATCH, the direction this pattern closes. 36 dashes is 36 characters of
     * the old {@code [A-Fa-f0-9-]} class, so the old pattern MATCHED and
     * {@code UUID.fromString} threw {@code IllegalArgumentException} straight out of
     * {@code preHandle} — a 500, because {@code GlobalExceptionHandler} has no
     * handler for it. Now the shape does not match, the interceptor abstains, and the
     * request falls to Spring's {@code @PathVariable} binder, which is a 400.
     * {@link OrganizationBrandingIntegrationTest} pins that status over HTTP.
     */
    @Test
    void malformedOrgId_doesNotThrow_soThePlatformCanAnswer400() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        String dashes = "-".repeat(36);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(pathRequest("/api/organizations/" + dashes + "/members"),
                response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200); // untouched — nothing was written
    }

    /**
     * UNDER-MATCH, pinned as an ACCEPTED RESIDUAL, not as a fix. {@code UUID.fromString}
     * is lenient about short group forms, so {@code 0-0-0-0-1} binds to a real
     * {@code 00000000-0000-0000-0000-000000000001} downstream while never matching the
     * canonical shape here — the interceptor abstains and the endpoint's own
     * {@code @PreAuthorize} is the only gate left. Closing this would break
     * {@link OrganizationBrandingIntegrationTest}, which uses precisely this path as the
     * falsifier for its read/write predicates. Tightening it is a separate decision.
     */
    @Test
    void lenientUuidForm_stillSkipsTheInterceptor_knownResidual() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        assertThat(UUID.fromString("0-0-0-0-1"))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(pathRequest("/api/organizations/0-0-0-0-1/members"),
                response, new Object())).isTrue();
    }

    private MockHttpServletRequest orgRequest(UUID orgId) {
        return pathRequest("/api/organizations/" + orgId + "/members");
    }

    private MockHttpServletRequest pathRequest(String uri) {
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
