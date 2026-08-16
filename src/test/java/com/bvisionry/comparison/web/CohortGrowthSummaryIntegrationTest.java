package com.bvisionry.comparison.web;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.InsightReportStatus;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.comparison.domain.CohortGrowthSummary;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.CohortGrowthSummaryDto;
import com.bvisionry.comparison.repository.CohortGrowthSummaryRepository;
import com.bvisionry.comparison.repository.ShiftNarrativeRepository;
import com.bvisionry.e2e.FakeChatResponseRegistry;
import com.bvisionry.e2e.FakeLangChainChatModel;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cohort-level growth report (redesign spec §4) end to end against the real
 * schema, with the deterministic e2e {@link FakeLangChainChatModel} as the only
 * transport.
 *
 * <p>Covered: the fail-loud empty-cohort guard, the GENERATING stub → COMPLETED
 * lifecycle with the coverage counters, what actually reaches the model
 * (anonymised by default, real names on request, all three §4 inputs present),
 * FAILED + retry, the premium gate, and the latest/history doors.
 *
 * <p>The async hop is driven by calling the service's synchronous entry point
 * directly — the same seam {@code ShiftNarrativeService.generateAllPillars}
 * gives its own specs — because a {@code @Transactional} spec never commits and
 * therefore never fires {@code AfterCommit}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@Import(CohortGrowthSummaryIntegrationTest.FakeAiTransport.class)
@EnabledIfDockerAvailable
class CohortGrowthSummaryIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Swaps the AI transport for the scripted fake — see ShiftNarrativeIntegrationTest. */
    @TestConfiguration
    static class FakeAiTransport {

        @Bean
        FakeChatResponseRegistry fakeChatResponseRegistry() {
            return new FakeChatResponseRegistry();
        }

        @Bean
        @Primary
        Lc4jChatModelProvider fakeLc4jChatModelProvider(FakeChatResponseRegistry registry,
                                                        AIConfigService configService,
                                                        ModelCapabilityRegistry capabilityRegistry) {
            return new Lc4jChatModelProvider(configService, capabilityRegistry) {
                @Override
                public ChatModel modelFor(String modelName, double temperature, int maxTokens) {
                    return new FakeLangChainChatModel(registry);
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private FakeChatResponseRegistry responses;
    @Autowired private CohortGrowthSummaryService service;
    @Autowired private CohortGrowthSummaryRepository summaries;
    @Autowired private ShiftNarrativeRepository narratives;
    @Autowired private ComparisonComputeService computeService;

    private Organization org;
    private User admin;
    private User alice;
    private User bob;
    private UUID cohortId;
    private UUID emptyCohortId;
    private UUID visionDistancePillar;
    private UUID visionBaselinePillar;

    @BeforeEach
    void seed() {
        responses.clear();
        org = saveOrg("Cohort Summary Org", SubscriptionTier.GROWTH);
        admin = saveUser("cohort.summary.admin@test.invalid", UserRole.ORG_ADMIN);
        // Two founders, deliberately created in the OPPOSITE order to their id
        // sort, so "Member 1" is proven to follow user.id and not insertion order.
        alice = saveUser("cohort.summary.alice@test.invalid", UserRole.MEMBER);
        bob = saveUser("cohort.summary.bob@test.invalid", UserRole.MEMBER);

        UUID baselinePipeline = insertPipeline("Baseline FRI");
        UUID distancePipeline = insertPipeline("Distance FRI");

        cohortId = insertCohort("Summary Cohort");
        emptyCohortId = insertCohort("Cohort With Nothing Computed");
        jdbc.update("""
                INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
                VALUES (?, ?, ?)
                """, cohortId, baselinePipeline, distancePipeline);

        visionBaselinePillar = insertPillar(baselinePipeline, "Vision", 0);
        visionDistancePillar = insertPillar(distancePipeline, "Vision", 0);
        UUID focusBaseline = insertPillar(baselinePipeline, "Focus & Flow", 1);
        UUID focusDistance = insertPillar(distancePipeline, "Focus & Flow", 1);

        enrol(alice, baselinePipeline, distancePipeline, visionBaselinePillar, visionDistancePillar,
                focusBaseline, focusDistance, "50.00", "70.00");
        enrol(bob, baselinePipeline, distancePipeline, visionBaselinePillar, visionDistancePillar,
                focusBaseline, focusDistance, "62.00", "55.00");

        computeService.recomputeCohort(cohortId, admin.getId());
    }

    @AfterEach
    void tearDown() {
        responses.clear();
        TestAuthentication.clear();
    }

    /* ==================================================== the fail-loud guard */

    @Test
    void aCohortWithNothingComputed_isRefusedSynchronously_andLeavesNoRow() {
        assertThatThrownBy(() -> service.generate(org.getId(), emptyCohortId, false, admin.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no computed comparisons");

        assertThat(summaries.findByOrgIdAndCohortIdOrderByGeneratedAtDesc(org.getId(), emptyCohortId))
                .isEmpty();
    }

    /* ========================================================== the lifecycle */

    @Test
    void generateLeavesAGeneratingStubWithCoverage_andTheRunCompletesIt() {
        approveNarrativeFor(alice);

        CohortGrowthSummaryDto stub = service.generate(org.getId(), cohortId, false, admin.getId());
        assertThat(stub.status()).isEqualTo(InsightReportStatus.GENERATING);
        assertThat(stub.report()).isNull();
        // Partial coverage is the normal case (§4): two members, one narrated.
        assertThat(stub.membersTotal()).isEqualTo(2);
        assertThat(stub.membersWithNarratives()).isEqualTo(1);
        assertThat(stub.includeNames()).isFalse();

        service.run(org.getId(), stub.id(), service.aggregate(org.getId(), cohortId, false).input());

        CohortGrowthSummary row = summaries.findByIdAndOrgId(stub.id(), org.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InsightReportStatus.COMPLETED);
        assertThat(row.getFailureReason()).isNull();
        assertThat(row.getAiModelUsed()).isNotBlank();
        assertThat(row.getReportJson())
                .containsKeys("overview", "sharedWins", "sharedRisks", "recommendations");
        // The counters stay stamped from the aggregation, not recomputed at completion.
        assertThat(row.getMembersWithNarratives()).isEqualTo(1);
    }

    @Test
    void aFailedRunIsRetryable_andNothingElseIs() {
        CohortGrowthSummaryDto stub = service.generate(org.getId(), cohortId, false, admin.getId());

        assertThatThrownBy(() -> service.retry(org.getId(), stub.id(), admin.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only FAILED");

        service.fail(org.getId(), stub.id(), "provider exploded");
        assertThat(summaries.findByIdAndOrgId(stub.id(), org.getId()).orElseThrow().getStatus())
                .isEqualTo(InsightReportStatus.FAILED);

        CohortGrowthSummaryDto retried = service.retry(org.getId(), stub.id(), admin.getId());
        assertThat(retried.id()).isEqualTo(stub.id());
        assertThat(retried.status()).isEqualTo(InsightReportStatus.GENERATING);
        assertThat(retried.failureReason()).isNull();
    }

    @Test
    void anUnparseableModelAnswer_marksTheRowFailedRatherThanCompletedWithRubbish() {
        CohortGrowthSummaryDto stub = service.generate(org.getId(), cohortId, false, admin.getId());
        // Every attempt (draft + the engine's repair retries) answers without the
        // required "overview" key, so the guardrail never passes.
        for (int i = 0; i < 6; i++) {
            responses.enqueue("{\"notTheContract\": \"nope\"}");
        }

        service.run(org.getId(), stub.id(), "irrelevant input");

        CohortGrowthSummary row = summaries.findByIdAndOrgId(stub.id(), org.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InsightReportStatus.FAILED);
        assertThat(row.getFailureReason()).isNotBlank();
        assertThat(row.getReportJson()).isNull();
    }

    /* ====================================================== what the model sees */

    @Test
    void theBlobCarriesAllThreeInputs() {
        approveNarrativeFor(alice);

        String input = service.aggregate(org.getId(), cohortId, false).input();

        assertThat(input)
                .contains("PER-MEMBER COMPARISON:")
                .contains("Vision: 50.00% → 70.00%")     // 1. before/after/delta per pillar
                .contains("APPROVED NARRATIVES:")
                .contains("A weekly review now runs every Monday")  // 2. the approved item text
                .contains("PILLAR AGGREGATE:")
                .contains("Average score after:")        // 3. the org-insights-style aggregate
                .contains("Common improvement areas:");
    }

    @Test
    void membersAreAnonymousByDefault_andNamedOnlyWhenAsked() {
        String anonymous = service.aggregate(org.getId(), cohortId, false).input();
        assertThat(anonymous).contains("Member 1").contains("Member 2")
                .doesNotContain(alice.getName()).doesNotContain(bob.getName());

        String named = service.aggregate(org.getId(), cohortId, true).input();
        assertThat(named).contains(alice.getName()).contains(bob.getName())
                .doesNotContain("Member 1");
    }

    @Test
    void whatTheAggregateSays_isExactlyWhatTheModelIsAsked() {
        CohortGrowthSummaryDto stub = service.generate(org.getId(), cohortId, true, admin.getId());
        String input = service.aggregate(org.getId(), cohortId, true).input();

        service.run(org.getId(), stub.id(), input);

        // contains, not equals: LangChain4j appends its own JSON-format instruction.
        assertThat(responses.lastUserMessage()).contains(input);
        assertThat(summaries.findByIdAndOrgId(stub.id(), org.getId()).orElseThrow().isIncludeNames())
                .isTrue();
    }

    /* ================================================================= doors */

    @Test
    void latestIs204BeforeThereIsOne_thenGenerateShowsUpInLatestAndHistory() throws Exception {
        TestAuthentication.authenticate(admin);

        mockMvc.perform(get("/api/organizations/{orgId}/cohorts/{cohortId}/growth-summary/latest",
                        org.getId(), cohortId))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/organizations/{orgId}/cohorts/{cohortId}/growth-summary/generate",
                        org.getId(), cohortId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeNames\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.membersTotal").value(2));

        mockMvc.perform(get("/api/organizations/{orgId}/cohorts/{cohortId}/growth-summary/latest",
                        org.getId(), cohortId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATING"));

        mockMvc.perform(get("/api/organizations/{orgId}/cohorts/{cohortId}/growth-summary/history",
                        org.getId(), cohortId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aFreeTierOrgIsRefused_byTheSamePremiumGateOrgInsightsUses() throws Exception {
        org.setSubscriptionTier(SubscriptionTier.FREE);
        organizationRepository.saveAndFlush(org);
        TestAuthentication.authenticate(admin);

        mockMvc.perform(post("/api/organizations/{orgId}/cohorts/{cohortId}/growth-summary/generate",
                        org.getId(), cohortId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeNames\":false}"))
                .andExpect(status().isForbidden());
    }

    /* =============================================================== fixtures */

    /** One APPROVED narrative, written straight to the table — the §4 input, not §6's job. */
    private void approveNarrativeFor(User founder) {
        ShiftNarrative narrative = new ShiftNarrative();
        narrative.setCohortId(cohortId);
        narrative.setOrgId(org.getId());
        narrative.setUserId(founder.getId());
        narrative.setBaselinePillarId(visionBaselinePillar);
        narrative.setDistancePillarId(visionDistancePillar);
        narrative.setPillarNameSnapshot("Vision");
        narrative.setItems(List.of(
                new ShiftNarrative.Item(NarrativeKind.NEW, "A weekly review now runs every Monday.")));
        narrative.setStatus(NarrativeStatus.APPROVED);
        narrative.setApprovedBy(admin.getId());
        narrative.setApprovedAt(Instant.now());
        narratives.saveAndFlush(narrative);
    }

    private void enrol(User founder, UUID baselinePipeline, UUID distancePipeline,
                       UUID visionBaseline, UUID visionDistance, UUID focusBaseline, UUID focusDistance,
                       String before, String after) {
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortId, founder.getId());
        UUID baselineSubmission = insertEvaluatedSubmission(founder, baselinePipeline, 60, "55.00");
        UUID distanceSubmission = insertEvaluatedSubmission(founder, distancePipeline, 0, "66.00");
        insertPillarEval(baselineSubmission, visionBaseline, before, "Emerging");
        insertPillarEval(distanceSubmission, visionDistance, after, "Strong");
        insertPillarEval(baselineSubmission, focusBaseline, "48.00", "Emerging");
        insertPillarEval(distanceSubmission, focusDistance, "58.00", "Strong");
    }

    private Organization saveOrg(String name, SubscriptionTier tier) {
        Organization o = new Organization();
        o.setName(name);
        o.setActive(true);
        o.setSubscriptionTier(tier);
        return organizationRepository.saveAndFlush(o);
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return userRepository.saveAndFlush(user);
    }

    private UUID insertCohort(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, ?, 'LAUNCHED')", id, name);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", id, org.getId());
        return id;
    }

    private UUID insertPipeline(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status, created_by) VALUES (?, ?, 'PUBLISHED', ?)",
                id, name, admin.getId());
        return id;
    }

    private UUID insertPillar(UUID pipelineId, String name, int order) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, ?, ?)",
                id, pipelineId, name, order);
        return id;
    }

    private UUID insertEvaluatedSubmission(User founder, UUID pipelineId, int daysAgo, String overallScore) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, org.getId(), founder.getId(), admin.getId());
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now() - make_interval(days => ?),
                        now() - make_interval(days => ?))
                """, submissionId, assignmentId, founder.getId(), daysAgo, daysAgo);
        jdbc.update("INSERT INTO overall_summaries (submission_id, overall_score_percentage) VALUES (?, ?::numeric)",
                submissionId, overallScore);
        return submissionId;
    }

    private void insertPillarEval(UUID submissionId, UUID pillarId, String score, String label) {
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage,
                                                maturity_label, ai_whats_working, ai_what_can_improve)
                VALUES (?, ?, ?::numeric, ?, ?::jsonb, ?::jsonb)
                """, submissionId, pillarId, score, label,
                "[\"A specific, evidenced strength described at length.\"]",
                "[\"A specific, evidenced gap described at length.\"]");
    }
}
