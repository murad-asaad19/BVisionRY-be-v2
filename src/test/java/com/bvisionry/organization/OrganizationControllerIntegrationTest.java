package com.bvisionry.organization;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.organization.dto.NudgeSettingsDto;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class OrganizationControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        // Users FK-reference organizations (users.organization_id), so children go first.
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void createOrganization_returns201_withDefaultGeneralSubOrg() throws Exception {
        String body = mockMvc.perform(post("/api/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Acme Corp", "description": "Test org"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Acme Corp")))
                .andExpect(jsonPath("$.subscriptionTier", is("FREE")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.memberCount", is(0)))
                // Members live in sub-orgs only — every root gets a "General" child.
                .andExpect(jsonPath("$.subOrganizationCount", is(1)))
                .andReturn().getResponse().getContentAsString();

        java.util.UUID rootId = java.util.UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        org.assertj.core.api.Assertions.assertThat(
                        organizationRepository.findByParentOrganizationIdOrderByNameAsc(rootId))
                .extracting(Organization::getName)
                .containsExactly("General");
    }

    @Test
    void createOrganization_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "description": "Test"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listOrganizations_returnsBothActiveAndSuspended() throws Exception {
        Organization active = new Organization();
        active.setName("Active");
        active.setActive(true);
        organizationRepository.save(active);

        Organization inactive = new Organization();
        inactive.setName("Inactive");
        inactive.setActive(false);
        organizationRepository.save(inactive);

        // Suspended orgs must show up in the listing — super-admin needs to see
        // them in the dashboard before deciding whether to reactivate.
        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(2)));
    }

    @Test
    void getById_returns200() throws Exception {
        Organization org = new Organization();
        org.setName("Test");
        org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(get("/api/organizations/" + org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Test")));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/organizations/" + java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeTier_returns200() throws Exception {
        Organization org = new Organization();
        org.setName("Tier Test");
        org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(patch("/api/organizations/" + org.getId() + "/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tier": "PREMIUM"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionTier", is("PREMIUM")));
    }

    @Test
    void softDelete_returns204() throws Exception {
        Organization org = new Organization();
        org.setName("Delete Me");
        org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(delete("/api/organizations/" + org.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void startTrial_returns200_setsPremium() throws Exception {
        Organization org = new Organization();
        org.setName("TrialOrg"); org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(post("/api/organizations/" + org.getId() + "/trial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\": 14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionTier", is("PREMIUM")))
                .andExpect(jsonPath("$.trialEndsAt", notNullValue()))
                .andExpect(jsonPath("$.displayState", is("TRIAL")));
    }

    @Test
    void startTrial_defaultDuration_isSevenDays() throws Exception {
        Organization org = new Organization();
        org.setName("DefaultTrial"); org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(post("/api/organizations/" + org.getId() + "/trial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void startTrial_alreadyOnTrial_returns400() throws Exception {
        Organization org = new Organization();
        org.setName("DupTrial"); org.setActive(true);
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        org.setTrialEndsAt(Instant.now().plus(2, ChronoUnit.DAYS));
        org = organizationRepository.save(org);

        mockMvc.perform(post("/api/organizations/" + org.getId() + "/trial")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void extendTrial_addsDays() throws Exception {
        Organization org = new Organization();
        org.setName("ExtendOrg"); org.setActive(true);
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        org.setTrialEndsAt(Instant.now().plus(2, ChronoUnit.DAYS));
        org = organizationRepository.save(org);

        mockMvc.perform(patch("/api/organizations/" + org.getId() + "/trial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"additionalDays\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialEndsAt", notNullValue()));
    }

    @Test
    void endTrialEarly_returnsFree() throws Exception {
        Organization org = new Organization();
        org.setName("EndEarly"); org.setActive(true);
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        org.setTrialEndsAt(Instant.now().plus(2, ChronoUnit.DAYS));
        org = organizationRepository.save(org);

        mockMvc.perform(delete("/api/organizations/" + org.getId() + "/trial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionTier", is("FREE")));
    }

    @Test
    void dashboard_returnsKpisAndTierMix() throws Exception {
        Organization a = new Organization(); a.setName("A"); a.setActive(true);
        a.setSubscriptionTier(SubscriptionTier.PREMIUM);
        organizationRepository.save(a);
        Organization b = new Organization(); b.setName("B"); b.setActive(false);
        organizationRepository.save(b);

        mockMvc.perform(get("/api/admin/organizations/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.totalOrgs", is(2)))
                .andExpect(jsonPath("$.kpis.activeCount", is(1)))
                .andExpect(jsonPath("$.kpis.suspendedCount", is(1)))
                .andExpect(jsonPath("$.tierMix.premium", is(1)))
                .andExpect(jsonPath("$.tierMix.free", is(1)))
                .andExpect(jsonPath("$.attention", isA(java.util.List.class)));
    }

    @Test
    void activity_returnsAuditEntriesForOrg() throws Exception {
        Organization org = new Organization(); org.setName("Active"); org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(get("/api/organizations/" + org.getId() + "/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", isA(java.util.List.class)));
    }

    // --- inactivity nudge window (roadmap §7 items 7 + 18) -------------------

    @Test
    void nudgeSettings_defaultToThePolicyWindowAndRoundTrip() throws Exception {
        Organization org = new Organization(); org.setName("Nudge Org"); org.setActive(true);
        org = organizationRepository.save(org);

        // Every existing org gets 14 from the migration's DEFAULT — no backfill,
        // no null-means-default branch (policy defaults.inactivity_threshold_days).
        mockMvc.perform(get("/api/organizations/" + org.getId() + "/nudge-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactivityNudgeDays", is(14)));

        mockMvc.perform(put("/api/organizations/" + org.getId() + "/nudge-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inactivityNudgeDays": 30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactivityNudgeDays", is(30)));

        mockMvc.perform(get("/api/organizations/" + org.getId() + "/nudge-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactivityNudgeDays", is(30)));
    }

    @Test
    void nudgeSettings_zeroIsAcceptedAsTheOrgsOffSwitch() throws Exception {
        Organization org = new Organization(); org.setName("Quiet Org"); org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(put("/api/organizations/" + org.getId() + "/nudge-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inactivityNudgeDays": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactivityNudgeDays", is(0)));
    }

    /**
     * The 90 cap is load-bearing: send-once is decided by reading the
     * notification history, which retention purges at 90 days, so a longer
     * window would silently re-nudge once the evidence was gone. Rejected at
     * the DTO (400) as well as by the V149 CHECK.
     */
    @Test
    void nudgeSettings_rejectsAWindowLongerThanNotificationRetention() throws Exception {
        Organization org = new Organization(); org.setName("Too Long"); org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(put("/api/organizations/" + org.getId() + "/nudge-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inactivityNudgeDays": 91}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/organizations/" + org.getId() + "/nudge-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inactivityNudgeDays": -1}
                                """))
                .andExpect(status().isBadRequest());
    }

    /**
     * The silent-disable hole: with a primitive {@code int} an absent field
     * binds to 0, which passes {@code @Min(0)} AND is the org-wide off switch —
     * so {@code PUT {}} returned 200 and killed an org's nudges with nothing in
     * the request saying so. Turning nudges off must be asked for.
     */
    @Test
    void nudgeSettings_emptyBody_isRefusedRatherThanReadAsZero() throws Exception {
        Organization org = new Organization(); org.setName("No Body"); org.setActive(true);
        org = organizationRepository.save(org);

        mockMvc.perform(put("/api/organizations/" + org.getId() + "/nudge-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/organizations/" + org.getId() + "/nudge-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inactivityNudgeDays": null}
                                """))
                .andExpect(status().isBadRequest());

        // …and the org still nudges.
        mockMvc.perform(get("/api/organizations/" + org.getId() + "/nudge-settings"))
                .andExpect(jsonPath("$.inactivityNudgeDays", is(14)));
    }

    /**
     * The binding cap is DERIVED from notification retention, not the DTO's
     * static {@code @Max(90)}. Send-once reads the notification history, which
     * NotificationRetentionJob purges at that property, so a window longer than
     * retention reads as "never nudged" once the evidence is gone and re-nudges
     * early. Tighten retention and this tightens with it.
     *
     * <p>Driven as a POJO with a {@link MockEnvironment} rather than a second
     * {@code @TestPropertySource} context: only {@code environment} is read
     * before the refusal, so the collaborators this path never reaches are left
     * null deliberately.
     */
    @Test
    void nudgeSettings_capDerivesFromNotificationRetention() {
        // NOT named `org` — that would shadow the `org.*` package below.
        Organization target = new Organization();
        target.setName("Tight Retention");
        target.setActive(true);
        java.util.UUID id = organizationRepository.save(target).getId();

        OrganizationController tightened = new OrganizationController(
                organizationService, null, null, null, null,
                new MockEnvironment().withProperty("bvisionry.notifications.retention-days", "30"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        tightened.updateNudgeSettings(id, new NudgeSettingsDto(45)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("30");

        // 45 clears the DTO's static @Max(90) — the derived cap is what refused
        // it. Non-positive retention disables the purge, so history is kept
        // forever and the same 45 is then perfectly safe.
        OrganizationController noPurge = new OrganizationController(
                organizationService, null, null, null, null,
                new MockEnvironment().withProperty("bvisionry.notifications.retention-days", "0"));
        org.assertj.core.api.Assertions.assertThat(
                        noPurge.updateNudgeSettings(id, new NudgeSettingsDto(45))
                                .getBody().inactivityNudgeDays())
                .isEqualTo(45);
    }

    @Test
    void nudgeSettings_unknownOrg_returns404() throws Exception {
        mockMvc.perform(get("/api/organizations/" + java.util.UUID.randomUUID() + "/nudge-settings"))
                .andExpect(status().isNotFound());
    }

    /**
     * The knob is deliberately NOT super-admin-only — an org admin owns their
     * own org's nudge cadence — so the org gate is the whole defense at the
     * method layer and has to be asserted, not assumed.
     */
    @Test
    void nudgeSettings_orgAdminOfAnotherOrg_isRefused() throws Exception {
        Organization own = new Organization(); own.setName("Own"); own.setActive(true);
        own = organizationRepository.save(own);
        Organization other = new Organization(); other.setName("Other"); other.setActive(true);
        other = organizationRepository.save(other);
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(get("/api/organizations/" + own.getId() + "/nudge-settings"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/organizations/" + other.getId() + "/nudge-settings"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/organizations/" + other.getId() + "/nudge-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inactivityNudgeDays": 1}
                                """))
                .andExpect(status().isForbidden());
    }
}
