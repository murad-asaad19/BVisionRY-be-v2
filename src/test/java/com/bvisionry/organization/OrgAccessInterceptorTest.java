package com.bvisionry.organization;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.OrgHierarchyPort;
import com.bvisionry.common.security.OrgScope;
import com.bvisionry.config.SecurityContextOrgScope;
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
    @Mock ObjectProvider<OrgScope> orgScopeProvider;
    OrgAccessInterceptor interceptor;

    private final UUID parentOrgId = UUID.randomUUID();
    private final UUID childOrgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Not every test path resolves the provider (super-admin short-circuits).
        lenient().when(orgHierarchyProvider.getIfAvailable()).thenReturn(orgHierarchy);
        // The interceptor delegates to the ONE predicate owner (OrgScope).
        lenient().when(orgScopeProvider.getIfAvailable())
                .thenReturn(new SecurityContextOrgScope(orgHierarchyProvider));
        interceptor = new OrgAccessInterceptor(orgScopeProvider);
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
     * A segment {@code UUID.fromString} rejects (36 dashes) can never resolve to an
     * org, so the interceptor ABSTAINS instead of throwing — a 500 out of
     * {@code preHandle} would mask the binder's 400
     * ({@code GlobalExceptionHandler} has no {@code IllegalArgumentException}
     * handler). {@link OrganizationBrandingIntegrationTest} pins that status over HTTP.
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
     * The interceptor's admission test is now the binder's: {@code UUID.fromString}
     * is lenient about short group forms, so {@code 0-0-0-0-1} binds to a real
     * {@code 00000000-0000-0000-0000-000000000001} downstream — and any spelling the
     * binder can resolve to an org id must be guarded here too, or the tenancy check
     * is skippable by re-spelling the id.
     */
    @Test
    void lenientUuidFormIsGuardedLikeTheBinderResolvesIt() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        UUID target = UUID.fromString("0-0-0-0-1");
        assertThat(target).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(orgHierarchy.isParentOf(parentOrgId, target)).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(pathRequest("/api/organizations/0-0-0-0-1/members"),
                response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    /**
     * A 36-character NON-canonical spelling: {@code UUID.fromString} truncates each
     * over-long group to its low bits, so this string resolves to the REAL org id
     * {@code 1a2b3c4d-5e6f-4a8b-9c0d-0e2f3a4b5c6d} — the old canonical-shape regex
     * abstained on it while the binder happily bound it, a re-spelling bypass of the
     * tenancy check.
     */
    @Test
    void nonCanonicalThirtySixCharUuid_isGuarded() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrgId);
        UUID target = UUID.fromString("01a2b3c4d-5e6f-4a8b-9c0d-e2f3a4b5c6d");
        assertThat(target).isEqualTo(UUID.fromString("1a2b3c4d-5e6f-4a8b-9c0d-0e2f3a4b5c6d"));
        when(orgHierarchy.isParentOf(parentOrgId, target)).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(
                pathRequest("/api/organizations/01a2b3c4d-5e6f-4a8b-9c0d-e2f3a4b5c6d/members"),
                response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
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
