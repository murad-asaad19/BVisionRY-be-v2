package com.bvisionry.assessment;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the raw-answers endpoint is restricted to the platform Super Admin —
 * Org Admins must be rejected by {@code @PreAuthorize} before the request ever
 * reaches {@link AssignmentService#getAssignmentAnswers}, so a member's actual
 * answers can't leak to org-level callers. The pillar-structure endpoint
 * (no answer content) stays available to Org Admins, since it only powers
 * the unlock-pillars picker.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Disabled("Integration test — needs Docker for Testcontainers Postgres. "
        + "Skipped on Windows + Docker Desktop; runs on Linux/CI where "
        + "Testcontainers connects to /var/run/docker.sock automatically.")
class AssignmentAnswersAccessIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void getAssignmentAnswers_orgAdmin_returns403() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, null);

        mockMvc.perform(get("/api/organizations/{orgId}/assignments/{id}/answers",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAssignmentAnswers_superAdmin_isNotForbidden() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);

        // No assignment exists for this random id, so the service 404s — the
        // point of this assertion is that the Super Admin clears the
        // @PreAuthorize gate, not that the lookup itself succeeds.
        mockMvc.perform(get("/api/organizations/{orgId}/assignments/{id}/answers",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAssignmentPillars_orgAdminInOwnOrg_isNotForbidden() throws Exception {
        Organization org = new Organization();
        org.setName("Test Org");
        org.setActive(true);
        Organization savedOrg = organizationRepository.save(org);

        TestAuthentication.authenticateAsOrgAdmin(userRepository, savedOrg);

        // No assignment exists for this random id, so the service 404s — the
        // point of this assertion is that pillar structure (unlike raw
        // answers) is NOT blocked for an Org Admin in their own org.
        mockMvc.perform(get("/api/organizations/{orgId}/assignments/{id}/pillars",
                        savedOrg.getId(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
