package com.bvisionry.organization;

import com.bvisionry.auth.OrgAccessGuard;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.security.OrgHierarchyPort;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.entity.Invitation;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end coverage for sub-organizations: CRUD under the parent's path,
 * hierarchy-aware tenancy (guard + interceptor) against REAL controllers,
 * tier/trial immutability on sub-orgs, suspend/delete cascades, and
 * invitation binding to a sub-org.
 *
 * <p>Follows the {@code EndpointAuthorizationMatrixIntegrationTest} pattern:
 * {@code @Transactional} rollback isolation, users created with per-role
 * emails, {@code addFilters = false} so {@code @PreAuthorize} + the
 * {@code OrgAccessInterceptor} are exercised without JWT plumbing.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class SubOrganizationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InvitationRepository invitationRepository;
    @Autowired private OrgHierarchyPort orgHierarchyPort;

    private Organization parentOrg;
    private Organization subOrg;
    private Organization otherOrg;

    @BeforeEach
    void seed() {
        // Re-pin OrgAccessGuard's static hierarchy port to THIS context's
        // adapter: other test classes in the same JVM create additional Spring
        // contexts (H2-backed @SpringBootTest slices) whose OrgAccessGuard
        // constructor overwrites the shared static with an adapter bound to a
        // different DataSource, which would 403 every traversal check here.
        new OrgAccessGuard(orgHierarchyPort);
        parentOrg = saveOrg("Parent Org", SubscriptionTier.PREMIUM, null);
        subOrg = saveOrg("Existing Sub", SubscriptionTier.FREE, parentOrg);
        otherOrg = saveOrg("Other Org", SubscriptionTier.FREE, null);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @Test
    void parentOrgAdmin_createsSubOrg_201_inheritsParentTier() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(post("/api/organizations/{orgId}/sub-organizations", parentOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"EMEA Division\", \"description\": \"Regional unit\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("EMEA Division")))
                .andExpect(jsonPath("$.parentOrganizationId", is(parentOrg.getId().toString())))
                .andExpect(jsonPath("$.parentOrganizationName", is("Parent Org")))
                // Own row starts FREE, but the effective plan is the parent's PREMIUM.
                .andExpect(jsonPath("$.subscriptionTier", is("FREE")))
                .andExpect(jsonPath("$.effectiveSubscriptionTier", is("PREMIUM")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.subOrganizationCount", is(0)));
    }

    @Test
    void createSubOrg_underSubOrg_returns400() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(post("/api/organizations/{orgId}/sub-organizations", subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Grandchild\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void siblingOrgAdmin_createSubOrgUnderForeignOrg_returns403() throws Exception {
        authenticate(UserRole.ORG_ADMIN, otherOrg, "other-admin@t.invalid");

        mockMvc.perform(post("/api/organizations/{orgId}/sub-organizations", parentOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Hijack\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void parentOrgAdmin_listsSubOrgs_withStats() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");
        saveUser(UserRole.MEMBER, subOrg, "sub-member@t.invalid");

        mockMvc.perform(get("/api/organizations/{orgId}/sub-organizations", parentOrg.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Existing Sub")))
                .andExpect(jsonPath("$[0].memberCount", is(1)))
                .andExpect(jsonPath("$[0].parentOrganizationId", is(parentOrg.getId().toString())))
                .andExpect(jsonPath("$[0].effectiveSubscriptionTier", is("PREMIUM")));
    }

    /** The load-bearing rename path: the PARENT org's admin renames a child. */
    @Test
    void parentOrgAdmin_renamesSubOrg_200() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(put("/api/organizations/{orgId}/sub-organizations/{subOrgId}",
                        parentOrg.getId(), subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Renamed Sub\", \"description\": \"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Renamed Sub")));

        assertThat(organizationRepository.findById(subOrg.getId()).orElseThrow().getName())
                .isEqualTo("Renamed Sub");
    }

    @Test
    void superAdmin_renamesSubOrg_viaSubOrgEndpoint_200() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(put("/api/organizations/{orgId}/sub-organizations/{subOrgId}",
                        parentOrg.getId(), subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"SA Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("SA Renamed")));
    }

    /** The pre-existing SUPER_ADMIN-only org update keeps working for sub-org rows. */
    @Test
    void superAdmin_renamesSubOrg_viaGenericOrgEndpoint_200() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(put("/api/organizations/{id}", subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Directly Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Directly Renamed")))
                .andExpect(jsonPath("$.parentOrganizationId", is(parentOrg.getId().toString())));
    }

    @Test
    void siblingOrgAdmin_renameForeignSubOrg_returns403() throws Exception {
        authenticate(UserRole.ORG_ADMIN, otherOrg, "other-admin@t.invalid");

        mockMvc.perform(put("/api/organizations/{orgId}/sub-organizations/{subOrgId}",
                        parentOrg.getId(), subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    /** Parentage is part of the identity: a child addressed under the wrong parent is 404. */
    @Test
    void updateSubOrg_wrongParent_returns404() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(put("/api/organizations/{orgId}/sub-organizations/{subOrgId}",
                        otherOrg.getId(), subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Wrong Parent\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSubOrg_wrongParent_returns404() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(delete("/api/organizations/{orgId}/sub-organizations/{subOrgId}",
                        otherOrg.getId(), subOrg.getId()))
                .andExpect(status().isNotFound());

        assertThat(organizationRepository.existsById(subOrg.getId())).isTrue();
    }

    @Test
    void parentOrgAdmin_deletesSubOrg_204() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");
        saveUser(UserRole.MEMBER, subOrg, "sub-member@t.invalid");

        mockMvc.perform(delete("/api/organizations/{orgId}/sub-organizations/{subOrgId}",
                        parentOrg.getId(), subOrg.getId()))
                .andExpect(status().isNoContent());

        assertThat(organizationRepository.existsById(subOrg.getId())).isFalse();
    }

    // -------------------------------------------------------------------------
    // Tier / trial immutability on sub-orgs
    // -------------------------------------------------------------------------

    @Test
    void changeTier_onSubOrg_returns400() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(patch("/api/organizations/{id}/tier", subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\": \"PREMIUM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startTrial_onSubOrg_returns400() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(post("/api/organizations/{id}/trial", subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Hierarchy-aware tenancy on EXISTING org-scoped endpoints
    // -------------------------------------------------------------------------

    @Test
    void parentOrgAdmin_readsSubOrgProfile_200() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(get("/api/organizations/{id}", subOrg.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Existing Sub")))
                .andExpect(jsonPath("$.parentOrganizationId", is(parentOrg.getId().toString())));
    }

    @Test
    void orgAdmin_readsOwnOrgProfile_200() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(get("/api/organizations/{id}", parentOrg.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subOrganizationCount", is(1)));
    }

    @Test
    void parentOrgAdmin_listsSubOrgMembers_200() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");
        saveUser(UserRole.MEMBER, subOrg, "sub-member@t.invalid");

        // Untouched MemberController — access flows purely from the
        // hierarchy-aware guard + interceptor.
        mockMvc.perform(get("/api/organizations/{orgId}/members", subOrg.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void subOrgAdmin_listsParentMembers_403() throws Exception {
        authenticate(UserRole.ORG_ADMIN, subOrg, "sub-admin@t.invalid");

        mockMvc.perform(get("/api/organizations/{orgId}/members", parentOrg.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberOfParent_listsSubOrgMembers_403() throws Exception {
        authenticate(UserRole.MEMBER, parentOrg, "parent-member@t.invalid");

        mockMvc.perform(get("/api/organizations/{orgId}/members", subOrg.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void siblingOrgAdmin_listsSubOrgMembers_403() throws Exception {
        authenticate(UserRole.ORG_ADMIN, otherOrg, "other-admin@t.invalid");

        mockMvc.perform(get("/api/organizations/{orgId}/members", subOrg.getId()))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Cascades
    // -------------------------------------------------------------------------

    @Test
    void toggleActive_onParent_cascadesToSubOrgAndMembers_bothDirections() throws Exception {
        authenticateSuperAdmin();
        User subMember = saveUser(UserRole.MEMBER, subOrg, "sub-member@t.invalid");

        mockMvc.perform(patch("/api/organizations/{id}/active", parentOrg.getId())
                        .param("active", "false"))
                .andExpect(status().isOk());

        assertThat(organizationRepository.findById(subOrg.getId()).orElseThrow().isActive()).isFalse();
        assertThat(userRepository.findById(subMember.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);

        mockMvc.perform(patch("/api/organizations/{id}/active", parentOrg.getId())
                        .param("active", "true"))
                .andExpect(status().isOk());

        assertThat(organizationRepository.findById(subOrg.getId()).orElseThrow().isActive()).isTrue();
        assertThat(userRepository.findById(subMember.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void toggleActive_onSubOrg_leavesParentUntouched() throws Exception {
        authenticateSuperAdmin();

        mockMvc.perform(patch("/api/organizations/{id}/active", subOrg.getId())
                        .param("active", "false"))
                .andExpect(status().isOk());

        assertThat(organizationRepository.findById(subOrg.getId()).orElseThrow().isActive()).isFalse();
        assertThat(organizationRepository.findById(parentOrg.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void hardDelete_parent_removesSubOrgsToo() throws Exception {
        authenticateSuperAdmin();
        saveUser(UserRole.MEMBER, subOrg, "sub-member@t.invalid");

        mockMvc.perform(delete("/api/organizations/{id}", parentOrg.getId()))
                .andExpect(status().isNoContent());

        assertThat(organizationRepository.existsById(parentOrg.getId())).isFalse();
        assertThat(organizationRepository.existsById(subOrg.getId())).isFalse();
    }

    // -------------------------------------------------------------------------
    // Invitations bind to the sub-org
    // -------------------------------------------------------------------------

    @Test
    void invitationToSubOrg_acceptedUser_belongsToSubOrg() throws Exception {
        User parentAdmin = authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(post("/api/organizations/{orgId}/members/invite", subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\": [\"invitee@t.invalid\"], \"role\": \"MEMBER\", "
                                + "\"invitedBy\": \"" + parentAdmin.getId() + "\"}"))
                .andExpect(status().isCreated());

        List<Invitation> invitations = invitationRepository.findByOrganizationId(subOrg.getId());
        assertThat(invitations).hasSize(1);

        mockMvc.perform(post("/api/invitations/{token}/accept", invitations.get(0).getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"New Invitee\", \"password\": \"secret-pass-123\"}"))
                .andExpect(status().isOk());

        User invitee = userRepository.findByEmail("invitee@t.invalid").orElseThrow();
        assertThat(invitee.getOrganization().getId()).isEqualTo(subOrg.getId());
        assertThat(invitee.getRole()).isEqualTo(UserRole.MEMBER);
    }

    // -------------------------------------------------------------------------
    // Members live in sub-orgs only: root-org restrictions + default "General"
    // -------------------------------------------------------------------------

    @Test
    void inviteMemberIntoRootOrg_returns400() throws Exception {
        User admin = authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(post("/api/organizations/{orgId}/members/invite", parentOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\": [\"member@t.invalid\"], \"role\": \"MEMBER\", "
                                + "\"invitedBy\": \"" + admin.getId() + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(invitationRepository.findByOrganizationId(parentOrg.getId())).isEmpty();
    }

    @Test
    void inviteOrgAdminIntoRootOrg_returns201() throws Exception {
        User admin = authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(post("/api/organizations/{orgId}/members/invite", parentOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\": [\"new-admin@t.invalid\"], \"role\": \"ORG_ADMIN\", "
                                + "\"invitedBy\": \"" + admin.getId() + "\"}"))
                .andExpect(status().isCreated());

        assertThat(invitationRepository.findByOrganizationId(parentOrg.getId())).hasSize(1);
    }

    @Test
    void generateJoinLink_onRootOrg_returns400() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(post("/api/organizations/{orgId}/join-link", parentOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryDays\": 7}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateJoinLink_onSubOrg_returns201() throws Exception {
        authenticate(UserRole.ORG_ADMIN, parentOrg, "parent-admin@t.invalid");

        mockMvc.perform(post("/api/organizations/{orgId}/join-link", subOrg.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryDays\": 7}"))
                .andExpect(status().isCreated());
    }

    @Test
    void moveMember_toRootOrg_returns400() throws Exception {
        authenticateSuperAdmin();
        User member = saveUser(UserRole.MEMBER, subOrg, "movable@t.invalid");

        mockMvc.perform(post("/api/users/{id}/move-organization", member.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetOrganizationId\": \"" + otherOrg.getId() + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findById(member.getId()).orElseThrow()
                .getOrganization().getId()).isEqualTo(subOrg.getId());
    }

    @Test
    void moveMember_toSubOrgOfAnotherOrg_returns200() throws Exception {
        authenticateSuperAdmin();
        Organization otherSub = saveOrg("Other Sub", SubscriptionTier.FREE, otherOrg);
        User member = saveUser(UserRole.MEMBER, subOrg, "movable@t.invalid");

        mockMvc.perform(post("/api/users/{id}/move-organization", member.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetOrganizationId\": \"" + otherSub.getId() + "\"}"))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(member.getId()).orElseThrow()
                .getOrganization().getId()).isEqualTo(otherSub.getId());
    }

    @Test
    void createOrganization_viaApi_autoCreatesGeneralSubOrg() throws Exception {
        authenticateSuperAdmin();

        String body = mockMvc.perform(post("/api/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Fresh Root\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subOrganizationCount", is(1)))
                .andReturn().getResponse().getContentAsString();

        UUID rootId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        List<Organization> children =
                organizationRepository.findByParentOrganizationIdOrderByNameAsc(rootId);
        assertThat(children).extracting(Organization::getName).containsExactly("General");
        assertThat(children.get(0).getSubscriptionTier()).isEqualTo(SubscriptionTier.FREE);
    }

    /** A sub-org member's /me reports the PARENT's (effective) tier, not the FREE sub-org row. */
    @Test
    void me_subOrgMember_reportsEffectiveParentTier() throws Exception {
        authenticate(UserRole.MEMBER, subOrg, "sub-member@t.invalid");

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId", is(subOrg.getId().toString())))
                .andExpect(jsonPath("$.organizationTier", is("PREMIUM")));
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private Organization saveOrg(String name, SubscriptionTier tier, Organization parent) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        org.setSubscriptionTier(tier);
        org.setParentOrganization(parent);
        return organizationRepository.save(org);
    }

    private User saveUser(UserRole role, Organization org, String email) {
        User user = new User();
        user.setEmail(email);
        user.setName(email);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    /** Distinct emails per persona — several tests authenticate more than one user. */
    private User authenticate(UserRole role, Organization org, String email) {
        User user = saveUser(role, org, email);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority(role.name()))));
        return user;
    }

    private void authenticateSuperAdmin() {
        User user = new User();
        user.setEmail("super-admin@t.invalid");
        user.setName("Super Admin");
        user.setRole(UserRole.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(saved, null,
                        List.of(new SimpleGrantedAuthority(UserRole.SUPER_ADMIN.name()))));
    }
}
