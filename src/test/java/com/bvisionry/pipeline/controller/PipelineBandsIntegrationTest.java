package com.bvisionry.pipeline.controller;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/pipelines/{id}/bands} — the measured party reading the
 * yardstick. Runs with the FILTER CHAIN ON so all three layers are live: the
 * route rule ({@code anyRequest().authenticated()}), the method gate
 * ({@code @PreAuthorize("isAuthenticated()")}, which must override the class's
 * SUPER_ADMIN), and the data-layer assignment check.
 *
 * <p>The load-bearing case is {@link #memberOfAnUnassignedOrgIsToldItDoesNotExist()}:
 * pipelines are platform-global, so without the assignment predicate any signed-in
 * member of any org could read every pipeline's bands by id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class PipelineBandsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private AssignmentRepository assignmentRepository;

    private Organization assignedOrg;
    private Organization otherOrg;
    private Pipeline pipeline;

    @BeforeEach
    void seed() {
        assignedOrg = organizationRepository.save(named(new Organization(), "Assigned Org"));
        otherOrg = organizationRepository.save(named(new Organization(), "Other Org"));

        // createdBy / assignedBy stay null: both are FKs to users(id) (nullable
        // since V34 / V137) and this test needs no author.
        pipeline = new Pipeline();
        pipeline.setName("Founder Readiness");
        pipeline.setStatus(PipelineStatus.PUBLISHED);
        pipeline.setPillars(new ArrayList<>(List.of(
                pillar(pipeline, "General Information", PillarType.PERSONAL, 0, Map.of()),
                pillar(pipeline, "Vision Clarity", PillarType.STANDARD, 1,
                        Map.of("Emerging", List.of(0, 59),
                                "Strong", List.of(60, 79),
                                "Elite", List.of(80, 100))))));
        pipeline = pipelineRepository.save(pipeline);

        Assignment provision = new Assignment();
        provision.setOrganization(assignedOrg);
        provision.setPipeline(pipeline);
        provision.setMaxCheckIns(1);
        assignmentRepository.save(provision);
    }

    private static Organization named(Organization org, String name) {
        org.setName(name);
        return org;
    }

    private static Pillar pillar(Pipeline pipeline, String name, PillarType type, int order,
                                 Map<String, List<Integer>> thresholds) {
        Pillar pillar = new Pillar();
        pillar.setPipeline(pipeline);
        pillar.setName(name);
        pillar.setType(type);
        pillar.setWeight(BigDecimal.ONE);
        pillar.setDisplayOrder(order);
        pillar.setMaturityThresholds(thresholds);
        return pillar;
    }

    /** In-memory principal, exactly what {@code JwtAuthenticationFilter} installs. */
    private static Authentication principal(UserRole role, Organization org) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(role.name().toLowerCase() + "@bands.invalid");
        user.setName(role.name());
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(role.name())));
    }

    private String bandsPath() {
        return "/api/pipelines/" + pipeline.getId() + "/bands";
    }

    @Test
    void memberOfAnAssignedOrgReadsTheBandsOfEveryScoredPillar() throws Exception {
        mockMvc.perform(get(bandsPath()).with(authentication(principal(UserRole.MEMBER, assignedOrg))))
                .andExpect(status().isOk())
                // The PERSONAL pillar carries no bands and is not scored, so it is absent.
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].pillarName", is("Vision Clarity")))
                .andExpect(jsonPath("$[0].maturityThresholds.Strong", contains(60, 79)));
    }

    @Test
    void memberOfAnUnassignedOrgIsToldItDoesNotExist() throws Exception {
        mockMvc.perform(get(bandsPath()).with(authentication(principal(UserRole.MEMBER, otherOrg))))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAssignedOrgStillCannotReadAPipelineThatWasPulledBackToDraft() throws Exception {
        pipeline.setStatus(PipelineStatus.DRAFT);
        pipelineRepository.save(pipeline);

        mockMvc.perform(get(bandsPath()).with(authentication(principal(UserRole.MEMBER, assignedOrg))))
                .andExpect(status().isNotFound());
    }

    @Test
    void superAdminReadsBandsWithNoOrgAndNoPublishedRequirement() throws Exception {
        pipeline.setStatus(PipelineStatus.DRAFT);
        pipelineRepository.save(pipeline);

        mockMvc.perform(get(bandsPath()).with(authentication(principal(UserRole.SUPER_ADMIN, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void anonymousIsRefusedByTheRouteRuleBeforeAnyController() throws Exception {
        mockMvc.perform(get(bandsPath())).andExpect(status().isUnauthorized());
    }
}
