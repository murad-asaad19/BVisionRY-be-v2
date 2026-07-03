package com.bvisionry.organization;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubscriptionTier;
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

    @BeforeEach
    void setUp() {
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void createOrganization_returns201() throws Exception {
        mockMvc.perform(post("/api/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Acme Corp", "description": "Test org"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Acme Corp")))
                .andExpect(jsonPath("$.subscriptionTier", is("FREE")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.memberCount", is(0)));
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
}
