package com.bvisionry.organization;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.entity.Invitation;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invitation token is a REDEEMABLE SECRET, and this pins exactly who gets to see it.
 *
 * <p>{@code POST /api/invitations/{token}/accept} is {@code permitAll()}, CSRF-exempt, and
 * mints a session on an account whose password the caller chooses. So whoever holds the
 * token completes that account — whoever invited it. When the org-scoped LISTING returned
 * the token, an ORG_ADMIN could finish an account created by a SUPER_ADMIN's invite of a
 * new ORG_ADMIN into their org and keep its credentials.
 *
 * <p>Every assertion here is made against the SERIALIZED HTTP RESPONSE, not a mapper or a
 * mock, because the wire is where the disclosure happens. The listing case additionally
 * scans the whole response body for the token's literal text, so moving the secret to a
 * different key would not sneak past.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class InvitationTokenDisclosureIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InvitationRepository invitationRepository;

    /**
     * Mocked so the invitation email's arguments can be captured (and so the real
     * transport is not attempted). It is the DELIVERY of the token that is verified
     * here — that the email still carries the secret the listing now withholds.
     */
    @MockitoBean private EmailService emailService;

    private Organization org;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Token Disclosure Org");
        org.setActive(true);
        org.setSubscriptionTier(SubscriptionTier.GROWTH);
        org = organizationRepository.save(org);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    // -------------------------------------------------------------------------
    // The defect itself
    // -------------------------------------------------------------------------

    /**
     * The escalation, played out: a SUPER_ADMIN invites a NEW ORG_ADMIN, then the org's
     * existing ORG_ADMIN lists invitations. Before the fix the listing handed over the
     * token and the second admin could complete the first's account.
     */
    @Test
    void theOrgScopedListingNeverDisclosesTheRedeemableToken() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
        inviteOrgAdmin("victim.admin@t.invalid");
        UUID token = onlyInvitation().getToken();

        TestAuthentication.authenticateAsOrgAdmin(userRepository, org);
        String body = mockMvc.perform(get("/api/organizations/{orgId}/invitations", org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                // Serialized as an explicit JSON null; `doesNotExist` is Spring's
                // assertion for "the path resolves to nothing", which covers both a
                // null value and an omitted key.
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // The strong form: the secret's literal text appears NOWHERE in the payload,
        // under any key. A rename would not evade this.
        assertThat(body)
                .as("the org-scoped invitation listing must not carry the redeemable token")
                .doesNotContain(token.toString());
    }

    /** The redaction removed the secret and nothing else — the admin console still renders. */
    @Test
    void theListingStillCarriesEverythingTheAdminConsoleReads() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
        inviteOrgAdmin("visible.fields@t.invalid");
        UUID invitationId = onlyInvitation().getId();

        TestAuthentication.authenticateAsOrgAdmin(userRepository, org);
        mockMvc.perform(get("/api/organizations/{orgId}/invitations", org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(invitationId.toString()))
                .andExpect(jsonPath("$[0].email").value("visible.fields@t.invalid"))
                .andExpect(jsonPath("$[0].role").value("ORG_ADMIN"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].organizationName").value("Token Disclosure Org"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].expiresAt").exists())
                .andExpect(jsonPath("$[0].viewCount").value(0))
                .andExpect(jsonPath("$[0].attemptCount").value(0))
                .andExpect(jsonPath("$[0].failedAttemptCount").value(0));
    }

    // -------------------------------------------------------------------------
    // …and the three channels that MUST keep the token
    // -------------------------------------------------------------------------

    /** The inviter created it, so the POST result may return it — e2e specs mint accounts this way. */
    @Test
    void theInviteResponseStillReturnsTheToken() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);

        String body = inviteOrgAdmin("post.response@t.invalid");
        UUID persisted = onlyInvitation().getToken();

        assertThat(body)
                .as("POST /members/invite returns the token to the caller that created it")
                .contains(persisted.toString());
    }

    /** The invitee's only channel. Whatever the listing stops showing, the email still delivers. */
    @Test
    void theInvitationEmailStillCarriesTheRedeemableToken() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
        inviteOrgAdmin("emailed@t.invalid");
        UUID persisted = onlyInvitation().getToken();

        ArgumentCaptor<String> acceptUrl = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendInvitationEmailAsync(
                eq("emailed@t.invalid"), anyString(), acceptUrl.capture(), any(Instant.class), anyString());
        assertThat(acceptUrl.getValue())
                .as("the invitation email links to the redeemable token")
                .endsWith("/invitations/" + persisted);
    }

    /** The public fetch echoes a token the caller already proved possession of in the path. */
    @Test
    void thePublicTokenFetchStillEchoesTheToken() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
        inviteOrgAdmin("public.fetch@t.invalid");
        UUID token = onlyInvitation().getToken();

        TestAuthentication.clear();
        mockMvc.perform(get("/api/invitations/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token.toString()));
    }

    /** Redemption by the real invitee is untouched — the whole point of not deleting the token. */
    @Test
    void theTokenStillRedeems() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
        inviteOrgAdmin("redeemer@t.invalid");
        UUID token = onlyInvitation().getToken();

        TestAuthentication.clear();
        mockMvc.perform(post("/api/invitations/{token}/accept", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Redeemer\", \"password\": \"secret-pass-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());

        User redeemed = userRepository.findByEmail("redeemer@t.invalid").orElseThrow();
        assertThat(redeemed.getOrganization().getId()).isEqualTo(org.getId());
        assertThat(redeemed.getRole()).isEqualTo(UserRole.ORG_ADMIN);
        assertThat(redeemed.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------

    /** Invites one ORG_ADMIN into the root org and returns the serialized POST response. */
    private String inviteOrgAdmin(String email) throws Exception {
        UUID inviter = ((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getId();
        return mockMvc.perform(post("/api/organizations/{orgId}/members/invite", org.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\": [\"" + email + "\"], \"role\": \"ORG_ADMIN\", "
                                + "\"invitedBy\": \"" + inviter + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private Invitation onlyInvitation() {
        List<Invitation> invitations = invitationRepository.findByOrganizationId(org.getId());
        assertThat(invitations).hasSize(1);
        return invitations.get(0);
    }
}
