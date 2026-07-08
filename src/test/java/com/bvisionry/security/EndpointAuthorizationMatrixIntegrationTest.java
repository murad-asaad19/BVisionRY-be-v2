package com.bvisionry.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role-matrix authorization tests for the high-value admin surfaces. Follows
 * the {@code AssignmentAnswersAccessIntegrationTest} pattern: no feature data
 * is seeded — each case asserts whether the caller clears the
 * {@code @PreAuthorize} gate (403 = rejected at the gate; any other status,
 * typically 404/200 on random ids, = cleared it). This pins the CURRENT
 * access rules so an accidentally widened guard fails the build.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional // each test rolls back, so fixed test-user emails can't collide across methods
@EnabledIfDockerAvailable
class EndpointAuthorizationMatrixIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private Organization ownOrg;
    private Organization otherOrg;

    @BeforeEach
    void seedOrgs() {
        ownOrg = saveOrg("Own Org");
        otherOrg = saveOrg("Other Org");
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.save(org);
    }

    private void expectForbidden(RequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isForbidden());
    }

    /** The caller cleared the @PreAuthorize gate — anything but 403 (random ids typically 404/200). */
    private void expectGateCleared(RequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(result ->
                assertThat(result.getResponse().getStatus())
                        .as("expected the @PreAuthorize gate to be cleared")
                        .isNotEqualTo(403));
    }

    // -------------------------------------------------------------------------
    // /api/organizations — platform org management (SUPER_ADMIN only, except activity)
    // -------------------------------------------------------------------------

    @Nested
    class OrganizationManagement {

        @Test
        void orgList_orgAdmin_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/organizations"));
        }

        @Test
        void orgList_member_returns403() throws Exception {
            TestAuthentication.authenticateAsMember(userRepository, ownOrg);
            expectForbidden(get("/api/organizations"));
        }

        @Test
        void orgList_superAdmin_clearsGate() throws Exception {
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            expectGateCleared(get("/api/organizations"));
        }

        @Test
        void orgActivity_orgAdminOwnOrg_clearsGate() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectGateCleared(get("/api/organizations/{id}/activity", ownOrg.getId()));
        }

        @Test
        void orgActivity_orgAdminOtherOrg_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{id}/activity", otherOrg.getId()));
        }
    }

    // -------------------------------------------------------------------------
    // /api/organizations/{orgId}/members — member operations
    // -------------------------------------------------------------------------

    @Nested
    class MemberOperations {

        @Test
        void memberList_orgAdminOwnOrg_clearsGate() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectGateCleared(get("/api/organizations/{orgId}/members", ownOrg.getId()));
        }

        @Test
        void memberList_orgAdminOtherOrg_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{orgId}/members", otherOrg.getId()));
        }

        @Test
        void memberList_memberOfSameOrg_returns403() throws Exception {
            TestAuthentication.authenticateAsMember(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{orgId}/members", ownOrg.getId()));
        }

        @Test
        void memberList_superAdminAnyOrg_clearsGate() throws Exception {
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            expectGateCleared(get("/api/organizations/{orgId}/members", otherOrg.getId()));
        }

        @Test
        void memberDetail_orgAdminOtherOrg_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{orgId}/members/{memberId}",
                    otherOrg.getId(), UUID.randomUUID()));
        }
    }

    // -------------------------------------------------------------------------
    // /api/organizations/{orgId}/dashboard — reporting / exports
    // -------------------------------------------------------------------------

    @Nested
    class ReportingExports {

        // These endpoints require a pipelineId query param. @RequestParam resolution
        // runs BEFORE the @PreAuthorize interceptor, so the param must be supplied or a
        // MissingServletRequestParameterException pre-empts the authorization check.

        @Test
        void dashboardOverview_orgAdminOwnOrg_clearsGate() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectGateCleared(get("/api/organizations/{orgId}/dashboard/overview", ownOrg.getId())
                    .param("pipelineId", UUID.randomUUID().toString()));
        }

        @Test
        void dashboardOverview_orgAdminOtherOrg_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{orgId}/dashboard/overview", otherOrg.getId())
                    .param("pipelineId", UUID.randomUUID().toString()));
        }

        @Test
        void dashboardOverview_member_returns403() throws Exception {
            TestAuthentication.authenticateAsMember(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{orgId}/dashboard/overview", ownOrg.getId())
                    .param("pipelineId", UUID.randomUUID().toString()));
        }

        @Test
        void memberResultExport_orgAdminOtherOrg_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{orgId}/dashboard/members/{userId}/results/{submissionId}/pdf",
                    otherOrg.getId(), UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        void insightsExcel_orgAdminOwnOrg_clearsGate() throws Exception {
            // Governed by the class-level gate — no method-level restriction.
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectGateCleared(get("/api/organizations/{orgId}/dashboard/insights/excel", ownOrg.getId())
                    .param("pipelineId", UUID.randomUUID().toString()));
        }

        @Test
        void insightsExcel_orgAdminOtherOrg_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/organizations/{orgId}/dashboard/insights/excel", otherOrg.getId())
                    .param("pipelineId", UUID.randomUUID().toString()));
        }

        @Test
        void insightsExcel_superAdmin_clearsGate() throws Exception {
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            expectGateCleared(get("/api/organizations/{orgId}/dashboard/insights/excel", ownOrg.getId())
                    .param("pipelineId", UUID.randomUUID().toString()));
        }

        @Test
        void dashboardOverview_missingRequiredParam_returns400() throws Exception {
            // A missing required @RequestParam is a client error; the GlobalExceptionHandler
            // maps MissingServletRequestParameterException to a 400 ProblemDetail. Note
            // @RequestParam resolution pre-empts @PreAuthorize, so this fires before authz —
            // an unauthorized caller omitting the param still learns nothing (non-2xx).
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            mockMvc.perform(get("/api/organizations/{orgId}/dashboard/overview", ownOrg.getId()))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // /api/ai-config — AI model settings + API-key management (SUPER_ADMIN only)
    // -------------------------------------------------------------------------

    @Nested
    class AiConfigManagement {

        @Test
        void aiConfig_orgAdmin_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/ai-config"));
        }

        @Test
        void aiConfigModels_member_returns403() throws Exception {
            TestAuthentication.authenticateAsMember(userRepository, ownOrg);
            expectForbidden(get("/api/ai-config/models"));
        }

        @Test
        void aiConfig_superAdmin_clearsGate() throws Exception {
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            expectGateCleared(get("/api/ai-config"));
        }
    }

    // -------------------------------------------------------------------------
    // /api/admin/public-assessments — public link administration (SUPER_ADMIN only)
    // -------------------------------------------------------------------------

    @Nested
    class PublicAssessmentAdministration {

        @Test
        void linkList_orgAdmin_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/admin/public-assessments"));
        }

        @Test
        void linkList_member_returns403() throws Exception {
            TestAuthentication.authenticateAsMember(userRepository, ownOrg);
            expectForbidden(get("/api/admin/public-assessments"));
        }

        @Test
        void linkResponses_orgAdmin_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(get("/api/admin/public-assessments/{linkId}/responses", UUID.randomUUID()));
        }

        @Test
        void retryResponse_orgAdmin_returns403() throws Exception {
            TestAuthentication.authenticateAsOrgAdmin(userRepository, ownOrg);
            expectForbidden(post("/api/admin/public-assessments/{linkId}/responses/{submissionId}/retry",
                    UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        void retryResponse_member_returns403() throws Exception {
            TestAuthentication.authenticateAsMember(userRepository, ownOrg);
            expectForbidden(post("/api/admin/public-assessments/{linkId}/responses/{submissionId}/retry",
                    UUID.randomUUID(), UUID.randomUUID()));
        }

        @Test
        void linkList_superAdmin_clearsGate() throws Exception {
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            expectGateCleared(get("/api/admin/public-assessments"));
        }

        @Test
        void retryResponse_superAdmin_clearsGate() throws Exception {
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            expectGateCleared(post("/api/admin/public-assessments/{linkId}/responses/{submissionId}/retry",
                    UUID.randomUUID(), UUID.randomUUID()));
        }
    }
}
