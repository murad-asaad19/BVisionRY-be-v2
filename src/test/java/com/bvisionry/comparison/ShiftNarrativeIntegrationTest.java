package com.bvisionry.comparison;

import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.aiengine.transport.ModelCapabilityRegistry;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.scoringconfig.NarrativeWording;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import com.bvisionry.comparison.dto.NarrativeRequests.UpdateNarrativeRequest;
import com.bvisionry.comparison.dto.NarrativeReviewResponse;
import com.bvisionry.comparison.dto.ShiftNarrativeDto;
import com.bvisionry.comparison.repository.ShiftNarrativeRepository;
import com.bvisionry.comparison.web.ComparisonComputeService;
import com.bvisionry.comparison.web.ComparisonQueryService;
import com.bvisionry.comparison.web.ShiftNarrativeService;
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
import org.junit.jupiter.api.Nested;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Qualitative Shift Narrative job + review gate end to end against the real
 * schema (spec §6), with a deterministic model: the e2e
 * {@link FakeLangChainChatModel} is wired in as the only transport, so every
 * assertion below is about OUR code, never about a provider's mood.
 *
 * <p>Covered: top-N selection by |shift|, the before-text floor producing NO row
 * and an inline sentence instead, the decline retry path, an unrecoverable
 * guardrail failure persisting nothing, on-demand generation, the draft →
 * approve → edit-back-to-draft state machine, approved-only member visibility,
 * recompute returning approvals to draft, the auto-approve toggle, and the
 * coach/admin doors.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@Import(ShiftNarrativeIntegrationTest.FakeAiTransport.class)
@EnabledIfDockerAvailable
class ShiftNarrativeIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * Swaps the AI transport for the scripted fake. Registered as {@code @Primary}
     * beside the production provider (which is {@code @Profile("!e2e & !mock")}
     * and therefore still present under {@code test}) — no bean-override flag
     * needed, and no e2e profile, so real method security stays in play.
     */
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
    @Autowired private ShiftNarrativeService narrativeService;
    @Autowired private ShiftNarrativeRepository narratives;
    @Autowired private ComparisonComputeService computeService;
    @Autowired private ComparisonQueryService queryService;
    @Autowired private com.bvisionry.comparison.web.MyGrowthExportService exports;

    private Organization org;
    private User founder;
    private User admin;
    private User coach;
    private UUID cohortId;
    private UUID distanceSubmission;

    /** distance-side pillar ids, by the shift they carry. */
    private UUID bigGain;      // +20  band_3
    private UUID bigDecline;   // -12  decline
    private UUID midGain;      // +9   band_2
    private UUID smallDecline; // -6   decline
    private UUID smallGain;    // +2   band_1
    private UUID thinBaseline; // +1   band_1, before-text under the floor

    @BeforeEach
    void seed() {
        responses.clear();
        org = saveOrg("Narrative Org");
        founder = saveUser("narrative.founder@test.invalid", UserRole.MEMBER, org);
        admin = saveUser("narrative.admin@test.invalid", UserRole.ORG_ADMIN, org);
        coach = saveUser("narrative.coach@test.invalid", UserRole.COACH, org);

        UUID baselinePipeline = insertPipeline("Baseline FRI");
        UUID distancePipeline = insertPipeline("Distance FRI");

        cohortId = insertCohort(org.getId(), "Narrative Cohort", founder.getId());
        jdbc.update("""
                INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
                VALUES (?, ?, ?)
                """, cohortId, baselinePipeline, distancePipeline);

        UUID baselineSubmission = insertEvaluatedSubmission(baselinePipeline, 60, "55.00");
        distanceSubmission = insertEvaluatedSubmission(distancePipeline, 0, "66.00");

        bigGain = pillarPair(baselinePipeline, distancePipeline, "Vision", 0,
                baselineSubmission, distanceSubmission, "50.00", "70.00", true);
        bigDecline = pillarPair(baselinePipeline, distancePipeline, "Energy & Motivation", 1,
                baselineSubmission, distanceSubmission, "62.00", "50.00", true);
        midGain = pillarPair(baselinePipeline, distancePipeline, "Focus & Flow", 2,
                baselineSubmission, distanceSubmission, "51.00", "60.00", true);
        smallDecline = pillarPair(baselinePipeline, distancePipeline, "Resilience", 3,
                baselineSubmission, distanceSubmission, "58.00", "52.00", true);
        smallGain = pillarPair(baselinePipeline, distancePipeline, "Legacy", 4,
                baselineSubmission, distanceSubmission, "40.00", "42.00", true);
        thinBaseline = pillarPair(baselinePipeline, distancePipeline, "Networks", 5,
                baselineSubmission, distanceSubmission, "44.00", "45.00", false);

        computeService.recomputeCohort(cohortId, admin.getId());
    }

    @AfterEach
    void tearDown() {
        responses.clear();
        TestAuthentication.clear();
    }

    /* ==================================================== generation */

    @Nested
    class Generation {

        @Test
        void generatesEveryPillarWithEnoughBeforeText() {
            int generated = narrativeService.generateAllPillars(cohortId, founder.getId());

            assertThat(generated).isEqualTo(5);
            assertThat(narratives.findByCohortIdAndUserId(cohortId, founder.getId()))
                    .extracting(ShiftNarrative::getDistancePillarId)
                    .containsExactlyInAnyOrder(bigGain, bigDecline, midGain, smallDecline, smallGain)
                    .doesNotContain(thinBaseline);
        }

        @Test
        void aSecondRunOnlyFillsTheGaps_neverDuplicates() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            narrativeService.delete(founder.getId(), narrativeOf(midGain).getId(), admin.getId());

            assertThat(narrativeService.generateAllPillars(cohortId, founder.getId())).isEqualTo(1);
            assertThat(narratives.findByCohortIdAndUserId(cohortId, founder.getId()))
                    .extracting(ShiftNarrative::getDistancePillarId)
                    .containsExactlyInAnyOrder(bigGain, bigDecline, midGain, smallDecline, smallGain);
        }

        @Test
        void everyGeneratedNarrativeLandsAsADraftWithFullProvenance() {
            narrativeService.generateAllPillars(cohortId, founder.getId());

            ShiftNarrative n = narrativeOf(bigGain);
            assertThat(n.getStatus()).isEqualTo(NarrativeStatus.DRAFT);
            assertThat(n.getApprovedAt()).isNull();
            assertThat(n.getGeneratedAt()).isNotNull();
            assertThat(n.getBody()).isNotBlank();
            assertThat(n.getKind()).isNotNull();
            assertThat(n.getAiModelUsed()).isNotBlank();
            assertThat(n.getAiTemperature()).isNotNull();
            assertThat(n.getAiPromptVersionId()).isNotNull();
            // §7: the wording + band it was generated with are stamped.
            assertThat(n.getConfigSnapshot()).isNotNull();
            assertThat(n.getConfigSnapshot().wording().notEnoughDataSentence()).isNotBlank();
            assertThat(n.getPillarNameSnapshot()).isEqualTo("Vision");
        }

        @Test
        void aPillarWithoutEnoughBeforeText_getsNoRowAndTheStandardSentenceInstead() {
            narrativeService.generateAllPillars(cohortId, founder.getId());

            assertThat(narratives.findByCohortIdAndUserIdAndDistancePillarId(
                    cohortId, founder.getId(), thinBaseline)).isEmpty();

            NarrativeReviewResponse review = narrativeService.review(founder.getId());
            assertThat(review.insufficientDataPillars())
                    .extracting(NarrativeReviewResponse.NarrativePillarOption::distancePillarId)
                    .containsExactly(thinBaseline);
            assertThat(review.notEnoughDataSentence())
                    .isEqualTo(NarrativeWording.defaultNotEnoughDataSentence());
            // Every pillar with real text was generated — nothing left to offer.
            assertThat(review.availablePillars()).isEmpty();
        }

        @Test
        void aDeclineWithoutAClosingAction_isRetriedWithTheInstruction_andThenPasses() {
            // First draft: prose, no way forward. Second: repaired.
            responses.enqueue("""
                    {"kind":"FADED","narrative":"The vivid pain driver is gone.","closingAction":""}
                    """);
            responses.enqueue("""
                    {"kind":"FADED","narrative":"The vivid pain driver is gone.",
                     "closingAction":"Reconnect with the original driver this month."}
                    """);

            ShiftNarrativeDto dto = narrativeService.generateForPillar(
                    founder.getId(), bigDecline, admin.getId());

            assertThat(dto.bandKey()).isEqualTo("decline");
            assertThat(dto.decline()).isTrue();
            assertThat(dto.kind()).isEqualTo(NarrativeKind.FADED.name());
            assertThat(dto.closingAction()).isEqualTo("Reconnect with the original driver this month.");
            assertThat(narrativeOf(bigDecline).getClosingAction()).isNotBlank();
        }

        @Test
        void aDeclineThatFailsTwice_persistsNothing() {
            responses.enqueue("""
                    {"kind":"FADED","narrative":"The driver is gone.","closingAction":""}
                    """);
            responses.enqueue("""
                    {"kind":"FADED","narrative":"Still gone.","closingAction":""}
                    """);

            assertThatThrownBy(() -> narrativeService.generateForPillar(
                    founder.getId(), bigDecline, admin.getId()))
                    .hasMessageContaining("could not be generated");

            assertThat(narratives.findByCohortIdAndUserIdAndDistancePillarId(
                    cohortId, founder.getId(), bigDecline)).isEmpty();
        }

        @Test
        void anUnclassifiableKind_isRetriedAndThenRefused() {
            // "Decline" is a BAND, not a kind — the exact confusion §6 calls out.
            responses.enqueue("""
                    {"kind":"Decline","narrative":"It went down.","closingAction":"Do better."}
                    """);
            responses.enqueue("""
                    {"kind":"Improved","narrative":"It went down.","closingAction":"Do better."}
                    """);

            assertThatThrownBy(() -> narrativeService.generateForPillar(
                    founder.getId(), bigGain, admin.getId()))
                    .hasMessageContaining("could not be generated");
            assertThat(narratives.findByCohortIdAndUserId(cohortId, founder.getId())).isEmpty();
        }

        @Test
        void onDemandGeneration_refusesAPillarWithoutEnoughBeforeText() {
            assertThatThrownBy(() -> narrativeService.generateForPillar(
                    founder.getId(), thinBaseline, admin.getId()))
                    .hasMessageContaining(NarrativeWording.defaultNotEnoughDataSentence());
        }

        @Test
        void onDemandGeneration_refusesAPillarThatAlreadyHasOne() {
            narrativeService.generateForPillar(founder.getId(), smallGain, admin.getId());
            assertThatThrownBy(() -> narrativeService.generateForPillar(
                    founder.getId(), smallGain, admin.getId()))
                    .hasMessageContaining("already has a narrative");
        }
    }

    /* =================================================== the review gate */

    @Nested
    class ReviewGate {

        @Test
        void draftBecomesApproved_withThe7bStamp() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            UUID id = narrativeOf(bigGain).getId();

            ShiftNarrativeDto approved = narrativeService.approve(founder.getId(), id, admin.getId());

            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(approved.approvedAt()).isNotNull();
            assertThat(approved.approvedBy()).isEqualTo(admin.getId());
            assertThat(approved.approvedByName()).isEqualTo(admin.getName());
        }

        @Test
        void editingAnApprovedNarrative_returnsItToDraftAndClearsTheApproval() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            UUID id = narrativeOf(bigGain).getId();
            narrativeService.approve(founder.getId(), id, admin.getId());

            ShiftNarrativeDto edited = narrativeService.update(founder.getId(), id,
                    new UpdateNarrativeRequest("Rewritten by the coach.", "RESOLVED", null),
                    coach.getId());

            assertThat(edited.status()).isEqualTo("DRAFT");
            assertThat(edited.body()).isEqualTo("Rewritten by the coach.");
            assertThat(edited.kind()).isEqualTo("RESOLVED");
            assertThat(edited.approvedAt()).isNull();
            assertThat(edited.approvedBy()).isNull();
            assertThat(edited.editedBy()).isEqualTo(coach.getId());
            assertThat(edited.editedByName()).isEqualTo(coach.getName());
            assertThat(edited.editedAt()).isNotNull();
        }

        @Test
        void aReviewerCannotEditTheWayForwardOffADecliningPillar() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            UUID id = narrativeOf(bigDecline).getId();

            assertThatThrownBy(() -> narrativeService.update(founder.getId(), id,
                    new UpdateNarrativeRequest("It dropped. Tough.", null, "  "), admin.getId()))
                    .hasMessageContaining("forward-looking next step");
        }

        @Test
        void draftsAreDeletable_approvedOnesAreNot() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            UUID draftId = narrativeOf(midGain).getId();
            UUID approvedId = narrativeOf(bigGain).getId();
            narrativeService.approve(founder.getId(), approvedId, admin.getId());

            narrativeService.delete(founder.getId(), draftId, admin.getId());
            assertThat(narratives.findById(draftId)).isEmpty();

            assertThatThrownBy(() -> narrativeService.delete(founder.getId(), approvedId, admin.getId()))
                    .hasMessageContaining("cannot be deleted");
        }

        @Test
        void anotherFoundersNarrativeIdIsA404_notAnEdit() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            UUID id = narrativeOf(bigGain).getId();

            assertThatThrownBy(() -> narrativeService.approve(admin.getId(), id, admin.getId()))
                    .isInstanceOf(com.bvisionry.common.exception.ResourceNotFoundException.class);
        }
    }

    /* ============================================= member visibility */

    @Nested
    class MemberVisibility {

        @Test
        void theMemberSeesNothingUntilApproved_andOnlyWhatIsApproved() {
            narrativeService.generateAllPillars(cohortId, founder.getId());

            MyComparisonResponse before = queryService.myComparison(founder.getId());
            assertThat(before.state()).isEqualTo("done");
            assertThat(before.comparison().narratives()).isEmpty();

            narrativeService.approve(founder.getId(), narrativeOf(bigGain).getId(), admin.getId());

            MyComparisonResponse after = queryService.myComparison(founder.getId());
            assertThat(after.comparison().narratives())
                    .extracting(ShiftNarrativeDto::distancePillarId)
                    .containsExactly(bigGain);
            assertThat(after.comparison().narratives())
                    .allMatch(n -> "APPROVED".equals(n.status()));
        }

        @Test
        void theExportsRenderTheApprovedNarrative() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            narrativeService.approve(founder.getId(), narrativeOf(bigGain).getId(), admin.getId());

            // The PDF template gained a narratives block; this is the check that
            // fails if its expressions ever drift from NarrativeRow.
            assertThat(exports.pdf(founder.getId(), true)).isNotEmpty();
            assertThat(exports.excel(founder.getId(), true)).isNotEmpty();
        }

        @Test
        void staffStillSeeTheDraftsThroughTheReviewEndpoint() {
            narrativeService.generateAllPillars(cohortId, founder.getId());

            assertThat(narrativeService.review(founder.getId()).narratives()).hasSize(4);
        }

        @Test
        void theMemberIsToldHowManyAreStillInReview_butNeverWhatTheySay() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            narrativeService.approve(founder.getId(), narrativeOf(bigGain).getId(), admin.getId());

            var cmp = queryService.myComparison(founder.getId()).comparison();
            assertThat(cmp.narratives()).hasSize(1);
            assertThat(cmp.draftNarrativeCount()).isEqualTo(4);
        }
    }

    /* ================================================ recompute + toggle */

    @Nested
    class RecomputeAndAutoApprove {

        @Test
        void recomputeReturnsApprovedNarrativesToDraft_withoutDeletingThem() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            UUID id = narrativeOf(bigGain).getId();
            narrativeService.approve(founder.getId(), id, admin.getId());

            computeService.recomputeCohort(cohortId, admin.getId());

            // The comparison row was rebuilt; the narratives survived it, because
            // they are anchored to the cohort + pillar, not to the comparison row.
            List<ShiftNarrative> after = narratives.findByCohortIdAndUserId(cohortId, founder.getId());
            assertThat(after).hasSize(5);
            assertThat(after).allMatch(n -> n.getStatus() == NarrativeStatus.DRAFT);
            assertThat(after).allMatch(n -> n.getApprovedAt() == null && n.getApprovedBy() == null);
            // And the member is back to seeing none of them.
            assertThat(queryService.myComparison(founder.getId()).comparison().narratives()).isEmpty();
        }

        @Test
        void aPillarThatBecomesADecline_isRestampedAndCanNoLongerBeApprovedWithoutANextStep() {
            // A neutral pillar whose narrative legitimately has no next step.
            responses.enqueue("""
                    {"kind":"CARRIED_FORWARD","narrative":"Steady, and a little sharper.",
                     "closingAction":""}
                    """);
            narrativeService.generateForPillar(founder.getId(), smallGain, admin.getId());
            assertThat(narrativeOf(smallGain).isDecline()).isFalse();
            assertThat(narrativeOf(smallGain).getClosingAction()).isNull();

            // The distance evaluation is corrected downward (42 → 30): the same
            // pillar is now a decline. Recompute re-stamps the narrative.
            jdbc.update("""
                    UPDATE pillar_evaluations SET score_percentage = 30.00
                     WHERE submission_id = ? AND pillar_id = ?
                    """, distanceSubmission, smallGain);
            computeService.recomputeCohort(cohortId, admin.getId());

            ShiftNarrative restamped = narrativeOf(smallGain);
            assertThat(restamped.isDecline()).isTrue();
            assertThat(restamped.getBandKey()).isEqualTo("decline");

            // §6 now binds it — and it binds at APPROVE, not only at edit, so a
            // draft written before the numbers moved cannot slip through.
            assertThatThrownBy(() -> narrativeService.approve(
                    founder.getId(), restamped.getId(), admin.getId()))
                    .hasMessageContaining("forward-looking next step");

            narrativeService.update(founder.getId(), restamped.getId(),
                    new UpdateNarrativeRequest("Steady, then it slipped.", null,
                            "Re-run the weekly review you dropped."),
                    admin.getId());
            assertThat(narrativeService.approve(founder.getId(), restamped.getId(), admin.getId())
                    .status()).isEqualTo("APPROVED");
        }

        @Test
        void approvingTwice_keepsTheOriginalStamp() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            UUID id = narrativeOf(bigGain).getId();
            ShiftNarrativeDto first = narrativeService.approve(founder.getId(), id, admin.getId());

            ShiftNarrativeDto again = narrativeService.approve(founder.getId(), id, coach.getId());

            assertThat(again.approvedAt()).isEqualTo(first.approvedAt());
            assertThat(again.approvedBy()).isEqualTo(admin.getId());
        }

        @Test
        void aRemappedPillarDetaches_stayingVisibleToStaffAndGoneFromTheMemberSurface() {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            narrativeService.approve(founder.getId(), narrativeOf(bigGain).getId(), admin.getId());

            // A SUPER_ADMIN unmaps the pair's Vision row, then the cohort is
            // recomputed: the comparison no longer measures that pillar.
            jdbc.update("DELETE FROM comparison_pillar_mappings WHERE distance_pillar_id = ?", bigGain);
            computeService.recomputeCohort(cohortId, admin.getId());

            NarrativeReviewResponse review = narrativeService.review(founder.getId());
            assertThat(review.narratives())
                    .filteredOn(ShiftNarrativeDto::detached)
                    .extracting(ShiftNarrativeDto::distancePillarId)
                    .containsExactly(bigGain);

            // The member never sees it again — not as an approved card, and not
            // as a "being reviewed" count.
            narratives.findByCohortIdAndUserIdAndDistancePillarId(cohortId, founder.getId(), bigGain)
                    .ifPresent(n -> narrativeService.approve(founder.getId(), n.getId(), admin.getId()));
            var cmp = queryService.myComparison(founder.getId()).comparison();
            assertThat(cmp.narratives())
                    .extracting(ShiftNarrativeDto::distancePillarId)
                    .doesNotContain(bigGain);
            assertThat(cmp.draftNarrativeCount()).isEqualTo(4);
        }

        @Test
        void autoApproveOn_landsNarrativesApprovedWithNoHumanAttribution() {
            enableAutoApprove();

            narrativeService.generateAllPillars(cohortId, founder.getId());

            List<ShiftNarrative> rows = narratives.findByCohortIdAndUserId(cohortId, founder.getId());
            assertThat(rows).hasSize(5);
            assertThat(rows).allMatch(n -> n.getStatus() == NarrativeStatus.APPROVED);
            assertThat(rows).allMatch(n -> n.getApprovedAt() != null && n.getApprovedBy() == null);
            assertThat(narrativeService.review(founder.getId()).autoApprove()).isTrue();
            assertThat(queryService.myComparison(founder.getId()).comparison().narratives()).hasSize(5);
        }
    }

    /* ============================================================ doors */

    @Nested
    class Doors {

        @Test
        void adminSeesTheReviewList_overHttp() throws Exception {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            TestAuthentication.authenticate(admin);

            mockMvc.perform(get("/api/organizations/{orgId}/members/{userId}/shift-narratives",
                            org.getId(), founder.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.narratives.length()").value(4))
                    .andExpect(jsonPath("$.notEnoughDataSentence").isNotEmpty());
        }

        @Test
        void adminCanApproveOverHttp() throws Exception {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            TestAuthentication.authenticate(admin);

            mockMvc.perform(post("/api/organizations/{orgId}/members/{userId}/shift-narratives/{id}/approve",
                            org.getId(), founder.getId(), narrativeOf(bigGain).getId())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        @Test
        void anUnassignedCoachIsRefused() throws Exception {
            narrativeService.generateAllPillars(cohortId, founder.getId());
            TestAuthentication.authenticate(coach);

            mockMvc.perform(get("/api/v1/coach/founders/{founderId}/shift-narratives", founder.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void anAssignedCoachReviewsLikeAnAdmin() throws Exception {
            jdbc.update("""
                    INSERT INTO coach_assignments (id, org_id, coach_id, cohort_id, assigned_by)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?)
                    """, org.getId(), coach.getId(), cohortId, admin.getId());
            narrativeService.generateAllPillars(cohortId, founder.getId());
            TestAuthentication.authenticate(coach);

            mockMvc.perform(get("/api/v1/coach/founders/{founderId}/shift-narratives", founder.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.narratives.length()").value(4));
        }

        @Test
        void aMemberCannotReachTheAdminReviewDoor() throws Exception {
            TestAuthentication.authenticate(founder);

            mockMvc.perform(get("/api/organizations/{orgId}/members/{userId}/shift-narratives",
                            org.getId(), founder.getId()))
                    .andExpect(status().isForbidden());
        }
    }

    /* ========================================================= fixtures */

    private void enableAutoApprove() {
        jdbc.update("""
                INSERT INTO platform_settings (key, value_text, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (key) DO UPDATE SET value_text = EXCLUDED.value_text
                """, NarrativeWording.NARRATIVE_KEY,
                """
                {"notEnoughDataSentence":"Not enough before-data yet.",
                 "declineCloseInstruction":"Close every decline with a next step.",
                 "autoApprove":true}
                """);
    }

    private ShiftNarrative narrativeOf(UUID distancePillarId) {
        return narratives.findByCohortIdAndUserIdAndDistancePillarId(
                cohortId, founder.getId(), distancePillarId).orElseThrow();
    }

    private Organization saveOrg(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setActive(true);
        return organizationRepository.saveAndFlush(o);
    }

    private User saveUser(String email, UserRole role, Organization organization) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(organization);
        return userRepository.saveAndFlush(user);
    }

    private UUID insertCohort(UUID orgId, String name, UUID memberId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, ?, 'LAUNCHED')", id, name);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", id, orgId);
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", id, memberId);
        return id;
    }

    private UUID insertPipeline(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status, created_by) VALUES (?, ?, 'PUBLISHED', ?)",
                id, name, founder.getId());
        return id;
    }

    private UUID insertEvaluatedSubmission(UUID pipelineId, int daysAgo, String overallScore) {
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

    /**
     * One name-matched pillar on both pipelines with its two evaluations.
     *
     * @param richBaselineText false = baseline text under the §6 length floor
     * @return the DISTANCE-side pillar id (the narrative anchor)
     */
    private UUID pillarPair(UUID baselinePipeline, UUID distancePipeline, String name, int order,
                            UUID baselineSubmission, UUID distanceSubmission,
                            String before, String after, boolean richBaselineText) {
        UUID baselinePillar = insertPillar(baselinePipeline, name, order);
        UUID distancePillar = insertPillar(distancePipeline, name, order);
        insertPillarEval(baselineSubmission, baselinePillar, before, "Emerging",
                richBaselineText
                        ? "[\"A specific, evidenced strength described at length in the intake.\"]"
                        : "[\"Fine.\"]",
                richBaselineText
                        ? "[\"A specific, evidenced gap described at length in the intake.\"]"
                        : "[\"Meh.\"]");
        insertPillarEval(distanceSubmission, distancePillar, after, "Strong",
                "[\"A specific, evidenced strength described at length in the distance.\"]",
                "[\"A specific, evidenced gap described at length in the distance.\"]");
        return distancePillar;
    }

    private UUID insertPillar(UUID pipelineId, String name, int order) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, ?, ?)",
                id, pipelineId, name, order);
        return id;
    }

    private void insertPillarEval(UUID submissionId, UUID pillarId, String score, String label,
                                  String workingJson, String improveJson) {
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage,
                                                maturity_label, ai_whats_working, ai_what_can_improve)
                VALUES (?, ?, ?::numeric, ?, ?::jsonb, ?::jsonb)
                """, submissionId, pillarId, score, label, workingJson, improveJson);
    }
}
