package com.bvisionry.pipeline.controller;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/pipelines/{id}/pillars/{id}/course-mappings} against the real
 * schema, with the FILTER CHAIN ON so all three layers are live: the route rule
 * ({@code anyRequest().authenticated()}), the method gate
 * ({@code @PreAuthorize("hasAuthority('SUPER_ADMIN')")}) and the data layer,
 * which scopes the pillar load by {@code (pillarId, pipelineId)}.
 *
 * <p>This is also the only place the raw catalog SQL runs. {@code CourseCatalogReadRepository}
 * depends on the SCHEMA rather than on catalog Java types (the ArchUnit ratchet
 * forbids the import), so a renamed column would compile perfectly and fail at
 * runtime — it has to meet a real {@code course} row to be evidence.
 *
 * <p>The row is INSERTED here rather than taken from the V77 catalog seed. The
 * Postgres container is a singleton shared by every integration class in the
 * JVM, and some of them empty {@code course}; depending on the seed made this
 * class pass alone and fail in the full suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class PillarCourseMappingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Pipeline pipeline;
    private Pillar scored;
    private Pillar personal;
    private UUID courseId;
    private final String courseTitle = "Runway Maths " + UUID.randomUUID();

    @BeforeEach
    void seed() {
        pipeline = new Pipeline();
        pipeline.setName("Founder Readiness");
        pipeline.setStatus(PipelineStatus.PUBLISHED);
        pipeline.setPillars(new ArrayList<>(List.of(
                pillar(pipeline, "General Information", PillarType.PERSONAL, 0, Map.of()),
                // Deliberately not in ascending key order — position is defined by
                // score, and jsonb does not preserve insertion order anyway.
                pillar(pipeline, "Vision Clarity", PillarType.STANDARD, 1,
                        Map.of("Elite", List.of(80, 100),
                                "Emerging", List.of(0, 59),
                                "Strong", List.of(60, 79))))));
        pipeline = pipelineRepository.save(pipeline);
        personal = pipeline.getPillars().get(0);
        scored = pipeline.getPillars().get(1);

        Organization owner = new Organization();
        owner.setName("Catalog Owner");
        // saveAndFlush, not save: the raw JDBC insert below is in the same
        // transaction but bypasses the persistence context, so a deferred
        // Hibernate INSERT would leave the org's FK dangling.
        owner = organizationRepository.saveAndFlush(owner);

        courseId = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title) VALUES (?, ?, ?, ?)",
                courseId, owner.getId(), "mapping-" + courseId, courseTitle);
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
    private static Authentication principal(UserRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(role.name().toLowerCase() + "@mapping.invalid");
        user.setName(role.name());
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(role.name())));
    }

    private String path(Pillar pillar) {
        return "/api/pipelines/" + pipeline.getId() + "/pillars/" + pillar.getId() + "/course-mappings";
    }

    private String body(int bandPosition, UUID course) {
        return """
                {"mappings":[{"bandPosition":%d,"courseId":"%s"}]}"""
                .formatted(bandPosition, course);
    }

    @Test
    void superAdminStoresARuleAndReadsItBackWithTheBandItActuallyMeans() throws Exception {
        mockMvc.perform(put(path(scored)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, courseId))
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                // Position 0 is the WEAKEST band, not the first jsonb key.
                .andExpect(jsonPath("$[0].bandLabel", is("Emerging")))
                .andExpect(jsonPath("$[0].bandMinScore", is(0)))
                .andExpect(jsonPath("$[0].bandMaxScore", is(59)))
                // The title comes from the raw catalog SQL meeting a real row.
                .andExpect(jsonPath("$[0].courseTitle", is(courseTitle)));

        mockMvc.perform(get(path(scored)).with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bandLabel", is("Emerging")));
    }

    @Test
    void shrinkingThePillarsBandsLeavesTheRuleInPlace_reportedWithNoBand() throws Exception {
        mockMvc.perform(put(path(scored)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, courseId))
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bandLabel", is("Elite")));

        // The admin later collapses three bands into two. Nothing cascades.
        scored.setMaturityThresholds(Map.of("Weak", List.of(0, 59), "Ready", List.of(60, 100)));
        pipelineRepository.save(pipeline);

        mockMvc.perform(get(path(scored)).with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bandPosition", is(2)))
                .andExpect(jsonPath("$[0].bandLabel", is(nullValue())))
                .andExpect(jsonPath("$[0].courseTitle", is(courseTitle)));
    }

    @Test
    void resavingTheSameRuleDoesNotCollideWithItsOwnPredecessor() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put(path(scored)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(1, courseId))
                            .with(authentication(principal(UserRole.SUPER_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Test
    void aBandPositionPastTheEndIsRefused() throws Exception {
        mockMvc.perform(put(path(scored)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(3, courseId))
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCourseThatDoesNotExistIsRefused() throws Exception {
        mockMvc.perform(put(path(scored)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, UUID.randomUUID()))
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePersonalPillarTakesNoRules() throws Exception {
        mockMvc.perform(put(path(personal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, courseId))
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPillarFromAnotherPipelineIsNotFound() throws Exception {
        String foreign = "/api/pipelines/" + UUID.randomUUID()
                + "/pillars/" + scored.getId() + "/course-mappings";

        mockMvc.perform(get(foreign).with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyRoleBelowSuperAdminIsRefused() throws Exception {
        for (UserRole role : List.of(UserRole.ORG_ADMIN, UserRole.INSTRUCTOR,
                UserRole.COACH, UserRole.MEMBER)) {
            mockMvc.perform(get(path(scored)).with(authentication(principal(role))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(path(scored)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(0, courseId))
                            .with(authentication(principal(role))))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * A copy that keeps a pillar's thresholds but drops the rules hanging off
     * them is a half copy, and the loss is silent — worth a test, not a comment.
     *
     * <p>PILLAR duplicate only. The PIPELINE-level clone paths
     * ({@code PipelineService#duplicate}, {@code #createNewVersion}) still lose
     * course rules: injecting anything into {@code PipelineService} changes its
     * constructor signature, which re-describes SIX frozen ArchUnit violations
     * on that constructor, and {@code frozen-violations/**} is never_write.
     * Recorded as a residual rather than bought with a store rewrite.
     */
    @Test
    void duplicatingThePillarCarriesItsCourseRulesWithIt() throws Exception {
        mockMvc.perform(put(path(scored)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, courseId))
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isOk());

        // Duplicating a pillar is requireDraft-gated (mapping it is not), so the
        // copy path only ever runs on a draft.
        pipeline.setStatus(PipelineStatus.DRAFT);
        pipelineRepository.save(pipeline);

        String copy = mockMvc.perform(post("/api/pipelines/" + pipeline.getId()
                        + "/pillars/" + scored.getId() + "/duplicate")
                        .with(csrf()).with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String clonedPillarId = com.jayway.jsonpath.JsonPath.read(copy, "$.id");

        mockMvc.perform(get("/api/pipelines/" + pipeline.getId()
                        + "/pillars/" + clonedPillarId + "/course-mappings")
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bandLabel", is("Strong")))
                .andExpect(jsonPath("$[0].courseId", is(courseId.toString())));
    }

    @Test
    void anonymousIsRefusedByTheRouteRuleBeforeAnyController() throws Exception {
        mockMvc.perform(get(path(scored))).andExpect(status().isUnauthorized());
    }
}
