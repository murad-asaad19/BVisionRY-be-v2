package com.bvisionry.comparison;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.event.EvaluationEvents;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.MappingSource;
import com.bvisionry.comparison.domain.PillarComparisonState;
import com.bvisionry.comparison.dto.CohortComparisonStatus;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import com.bvisionry.comparison.dto.PillarMappingResponse;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
import com.bvisionry.comparison.web.ComparisonComputeService;
import com.bvisionry.comparison.web.ComparisonMappingService;
import com.bvisionry.comparison.web.ComparisonQueryService;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The distance-comparison object end to end against the real schema (spec §5,
 * §7): compute-on-evaluation, one-sided pillar states, the report lifecycle
 * guard, the explicit recompute with current config, the mapping seed +
 * override invariants, config validation field errors, and tenancy.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class ComparisonIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ComparisonComputeService computeService;
    @Autowired private ComparisonMappingService mappingService;
    @Autowired private ComparisonQueryService queryService;
    @Autowired private FounderComparisonRepository comparisons;
    @Autowired private FounderComparisonPillarRepository comparisonPillars;

    private Organization orgA;
    private Organization orgB;
    private User founder1;   // both submissions evaluated → comparison
    private User founder2;   // baseline only → pending
    private UUID cohortId;
    private UUID baselinePipelineId;
    private UUID distancePipelineId;
    private UUID baselineVision;
    private UUID baselineFocus;
    private UUID baselineLegacy;
    private UUID distanceVision;
    private UUID distanceFocus;
    private UUID distanceResilience;
    private UUID founder1BaselineSubmission;
    private UUID founder1DistanceSubmission;
    private UUID founder2BaselineSubmission;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Comparison Org A");
        orgB = saveOrg("Comparison Org B");
        founder1 = saveUser("founder.one@test.invalid", UserRole.MEMBER, orgA);
        founder2 = saveUser("founder.two@test.invalid", UserRole.MEMBER, orgA);

        baselinePipelineId = insertPipeline("Baseline FRI");
        distancePipelineId = insertPipeline("Distance FRI");
        baselineVision = insertPillar(baselinePipelineId, "Vision", 0);
        baselineFocus = insertPillar(baselinePipelineId, "Focus & Flow", 1);
        baselineLegacy = insertPillar(baselinePipelineId, "Legacy", 2);
        // Name-mismatched casing on purpose: the seed matches case-insensitively.
        distanceVision = insertPillar(distancePipelineId, "vision", 0);
        distanceFocus = insertPillar(distancePipelineId, "Focus & Flow", 1);
        distanceResilience = insertPillar(distancePipelineId, "Resilience", 2);

        cohortId = insertCohort(orgA.getId(), "Cohort 12", founder1.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortId, founder2.getId());
        jdbc.update("""
                INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
                VALUES (?, ?, ?)
                """, cohortId, baselinePipelineId, distancePipelineId);
        // The cohort's own instruments: LIVE ASSESSMENT tasks referencing the
        // two designated pipelines — what CohortInstruments scopes the
        // member-anchored trajectory to (operator rule 2026-08-14).
        UUID moduleId = UUID.randomUUID();
        jdbc.update("INSERT INTO program_modules (id, cohort_id, name) VALUES (?, ?, 'Assessments')",
                moduleId, cohortId);
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, task_type, ref_id)
                VALUES (?, ?, 'Baseline scan', 'LIVE', 'ASSESSMENT', ?)
                """, UUID.randomUUID(), moduleId, baselinePipelineId);
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, task_type, ref_id)
                VALUES (?, ?, 'Distance scan', 'LIVE', 'ASSESSMENT', ?)
                """, UUID.randomUUID(), moduleId, distancePipelineId);

        // founder1: baseline 58.00 (60 days ago) → distance 71.50 (now).
        founder1BaselineSubmission = insertEvaluatedSubmission(founder1, baselinePipelineId, 60,
                new BigDecimal("58.00"));
        insertPillarEval(founder1BaselineSubmission, baselineVision, "50.00", "Emerging");
        insertPillarEval(founder1BaselineSubmission, baselineFocus, "60.00", "Strong");
        insertPillarEval(founder1BaselineSubmission, baselineLegacy, "40.00", "Emerging");
        // A baseline RETAKE (newer, higher): baseline resolution is EARLIEST
        // evaluated, so every assertion on overallBefore = 58.00 proves the
        // retake never shifts the intake baseline.
        insertEvaluatedSubmission(founder1, baselinePipelineId, 10, new BigDecimal("65.00"));
        founder1DistanceSubmission = insertEvaluatedSubmission(founder1, distancePipelineId, 0,
                new BigDecimal("71.50"));
        insertPillarEval(founder1DistanceSubmission, distanceVision, "62.00", "Strong");
        insertPillarEval(founder1DistanceSubmission, distanceFocus, "78.00", "Strong");
        insertPillarEval(founder1DistanceSubmission, distanceResilience, "55.00", "Emerging");

        // founder2: baseline only.
        founder2BaselineSubmission = insertEvaluatedSubmission(founder2, baselinePipelineId, 30,
                new BigDecimal("44.00"));
        insertPillarEval(founder2BaselineSubmission, baselineVision, "44.00", "Emerging");
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    private void computeFounder1() {
        computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                founder1DistanceSubmission, founder1.getId(), Map.of()));
    }

    /* -------------------------------------------------------- the compute */

    @Nested
    class ComputeOnEvaluation {

        @Test
        void happyPath_storesOverallAndPillarSnapshot() {
            computeFounder1();

            FounderComparison c = comparisons
                    .findByCohortIdAndUserId(cohortId, founder1.getId()).orElseThrow();
            assertThat(c.getOrgId()).isEqualTo(orgA.getId());
            assertThat(c.getOverallBefore()).isEqualByComparingTo("58.00");
            assertThat(c.getOverallAfter()).isEqualByComparingTo("71.50");
            assertThat(c.getOverallDelta()).isEqualByComparingTo("13.50");
            assertThat(c.getOverallBandKey()).isEqualTo("band_2");
            assertThat(c.getOverallBandLabel()).isEqualTo("Moderate");
            assertThat(c.getConfigSnapshot()).isNotEmpty();
            assertThat(c.getComputedAt()).isNotNull();

            var rows = comparisonPillars.findByComparisonId(c.getId());
            assertThat(rows).hasSize(4);

            var vision = rows.stream()
                    .filter(r -> baselineVision.equals(r.getBaselinePillarId())).findFirst()
                    .orElseThrow();
            assertThat(vision.getState()).isEqualTo(PillarComparisonState.MAPPED);
            assertThat(vision.getDistancePillarId()).isEqualTo(distanceVision);
            assertThat(vision.getBeforePct()).isEqualByComparingTo("50.00");
            assertThat(vision.getAfterPct()).isEqualByComparingTo("62.00");
            assertThat(vision.getDelta()).isEqualByComparingTo("12.00");
            assertThat(vision.getBandLabel()).isEqualTo("Moderate");
            assertThat(vision.getMaturityBefore()).isEqualTo("Emerging");
            assertThat(vision.getMaturityAfter()).isEqualTo("Strong");

            var legacy = rows.stream()
                    .filter(r -> baselineLegacy.equals(r.getBaselinePillarId())).findFirst()
                    .orElseThrow();
            assertThat(legacy.getState()).isEqualTo(PillarComparisonState.NOT_REMEASURED);
            assertThat(legacy.getBeforePct()).isEqualByComparingTo("40.00");
            assertThat(legacy.getAfterPct()).isNull();
            assertThat(legacy.getMaturityBefore()).isEqualTo("Emerging");

            var resilience = rows.stream()
                    .filter(r -> distanceResilience.equals(r.getDistancePillarId())).findFirst()
                    .orElseThrow();
            assertThat(resilience.getState()).isEqualTo(PillarComparisonState.NEWLY_MEASURED);
            assertThat(resilience.getAfterPct()).isEqualByComparingTo("55.00");
            assertThat(resilience.getBeforePct()).isNull();
            assertThat(resilience.getPillarNameSnapshot()).isEqualTo("Resilience");
        }

        @Test
        void baselineOnlyMember_getsNoComparison_andPendingState() {
            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    founder2BaselineSubmission, founder2.getId(), Map.of()));

            assertThat(comparisons.findByCohortIdAndUserId(cohortId, founder2.getId())).isEmpty();

            MyComparisonResponse my = queryService.myComparison(founder2.getId());
            assertThat(my.state()).isEqualTo("pending");
            assertThat(my.comparison()).isNull();
            assertThat(my.cohortId()).isEqualTo(cohortId);
            assertThat(my.trajectory()).hasSize(1);
            assertThat(my.trajectory().get(0).overallScore()).isEqualByComparingTo("44.00");
        }

        /** The rare ordering: the BASELINE evaluates after the distance — the
         *  listener fired with the baseline submission still computes. */
        @Test
        void baselineEvaluatedAfterDistance_stillComputes() {
            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    founder1BaselineSubmission, founder1.getId(), Map.of()));

            FounderComparison c = comparisons
                    .findByCohortIdAndUserId(cohortId, founder1.getId()).orElseThrow();
            assertThat(c.getOverallBefore()).isEqualByComparingTo("58.00");
            assertThat(c.getOverallAfter()).isEqualByComparingTo("71.50");
        }

        /** Compute-once: a second evaluation event never restates an existing comparison. */
        @Test
        void secondEvaluationEvent_isANoOpOnceComputed() {
            computeFounder1();
            FounderComparison first = comparisons
                    .findByCohortIdAndUserId(cohortId, founder1.getId()).orElseThrow();

            computeFounder1();
            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    founder1BaselineSubmission, founder1.getId(), Map.of()));

            FounderComparison after = comparisons
                    .findByCohortIdAndUserId(cohortId, founder1.getId()).orElseThrow();
            assertThat(after.getId()).isEqualTo(first.getId());
            assertThat(after.getComputedAt()).isEqualTo(first.getComputedAt());
        }

        /**
         * V167: a member-facing growth anchor comes only from a cohort the
         * member can SEE (LAUNCHED/COMPLETED) — a newer, fully-designated
         * DRAFT must not become the anchor before launch.
         */
        @Test
        void aNewerDraftPairCohort_neverBecomesTheMembersAnchor() {
            UUID draft = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO cohorts (id, name, status, created_at)
                    VALUES (?, 'Draft pair cohort', 'DRAFT', now() + interval '1 day')
                    """, draft);
            jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                    draft, orgA.getId());
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    draft, founder2.getId());
            jdbc.update("""
                    INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
                    VALUES (?, ?, ?)
                    """, draft, baselinePipelineId, distancePipelineId);

            assertThat(queryService.myComparison(founder2.getId()).cohortId())
                    .as("anchor stays on the launched cohort, not the newer draft")
                    .isEqualTo(cohortId);
        }

        /**
         * Operator rule 2026-08-14: a growth surface anchored to a designated
         * cohort shows ONLY that cohort's instruments. A sitting on an
         * unrelated scan (a direct assignment) must not appear between the
         * cohort's baseline and distance — live QA caught exactly that. The
         * exports and the UI strip read this same payload, so one scoping here
         * covers all of them.
         */
        @Test
        void trajectoryIsScopedToTheDesignatedCohortsInstruments() {
            UUID scanPipeline = insertPipeline("Magic Number Scan");
            insertEvaluatedSubmission(founder1, scanPipeline, 30, new BigDecimal("90.00"));

            assertThat(queryService.myComparison(founder1.getId()).trajectory())
                    .extracting(MyComparisonResponse.TrajectoryPoint::pipelineName)
                    .containsExactly("Baseline FRI", "Baseline FRI", "Distance FRI");
        }

        /**
         * The growth card names what a member is measured ON before they have
         * a single score, so the payload carries the BASELINE instrument's
         * pillars in every state — and only the scored ones. A PERSONAL pillar
         * (the FRI's "General Information" section) collects data, carries no
         * weight and no maturity thresholds; rotating it through a growth
         * surface as if it were a mindset is the bug this pins.
         */
        @Test
        void carriesTheBaselinesScoredPillars_neverThePersonalSection() {
            jdbc.update("UPDATE pillars SET description = ? WHERE id = ?",
                    "Where you are going, as a picture others can act on.", baselineVision);
            UUID personal = insertPillar(baselinePipelineId, "General Information", 9);
            jdbc.update("UPDATE pillars SET type = 'PERSONAL' WHERE id = ?", personal);

            assertThat(queryService.myComparison(founder1.getId()).pillars())
                    .extracting(MyComparisonResponse.PillarBlurb::name)
                    .containsExactly("Vision", "Focus & Flow", "Legacy");
            assertThat(queryService.myComparison(founder1.getId()).pillars())
                    .first()
                    .extracting(MyComparisonResponse.PillarBlurb::description)
                    .isEqualTo("Where you are going, as a picture others can act on.");
        }

        /** The §5 guard: no designated pair → never tease a pending report. */
        @Test
        void memberWithoutDesignatedPair_getsNoneState() {
            User founder3 = saveUser("founder.three@test.invalid", UserRole.MEMBER, orgA);
            insertCohort(orgA.getId(), "Pairless cohort", founder3.getId());

            MyComparisonResponse my = queryService.myComparison(founder3.getId());
            assertThat(my.state()).isEqualTo("none");
            assertThat(my.comparison()).isNull();
            assertThat(my.cohortId()).isNull();
        }
    }

    /* ---------------------------------------------------- pair designation */

    @Nested
    class PairDesignation {

        /**
         * The pair is DERIVED from the cohort's BASELINE/DISTANCE milestone
         * tasks (V180), so the settings PUT cannot designate: the two pipeline
         * ids it is handed are ignored and the stored pair is echoed back
         * unchanged. Nothing else on the endpoint changes — this is the pacing
         * write, and the Curriculum tab is where the instrument is chosen.
         */
        @Test
        void theSettingsPutCannotRedesignateThePair() throws Exception {
            // Spec §13: program authoring is platform-scoped, super admin only.
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            mockMvc.perform(put("/api/cohorts/" + cohortId + "/program/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"stageLabel":"Sprint","dripEnabled":true,"dueSoonDays":3,
                                     "baselinePipelineId":"%s","distancePipelineId":"%s"}
                                    """.formatted(baselinePipelineId, baselinePipelineId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stageLabel", is("Sprint")))
                    .andExpect(jsonPath("$.baselinePipelineId", is(baselinePipelineId.toString())))
                    .andExpect(jsonPath("$.distancePipelineId", is(distancePipelineId.toString())));
        }
    }

    /* -------------------------------------------- same-pipeline pairs (D1) */

    @Nested
    class SamePipelinePair {

        private User founder;
        private UUID samePairCohortId;
        private UUID distanceTaskId;

        @BeforeEach
        void seedSamePipelineCohort() {
            founder = saveUser("founder.same@test.invalid", UserRole.MEMBER, orgA);
            samePairCohortId = insertCohort(orgA.getId(), "Same-instrument cohort", founder.getId());
            jdbc.update("""
                    INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
                    VALUES (?, ?, ?)
                    """, samePairCohortId, baselinePipelineId, baselinePipelineId);
            UUID moduleId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_modules (id, cohort_id, name)
                    VALUES (?, ?, 'Distance module')
                    """, moduleId, samePairCohortId);
            distanceTaskId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_tasks (id, module_id, name, status, task_type, ref_id, milestone_role)
                    VALUES (?, ?, 'Distance assessment', 'LIVE', 'ASSESSMENT', ?, 'DISTANCE')
                    """, distanceTaskId, moduleId, baselinePipelineId);
        }

        /**
         * The FRI 58 → 64 → 71 story on ONE pipeline: baseline = earliest
         * evaluated, the middle check-in is ignored for the distance, and the
         * distance = the submission TAGGED to the DISTANCE milestone task —
         * even though a newer untagged submission exists (tag beats latest).
         */
        @Test
        void baselineEarliest_checkinIgnored_distanceIsTheTaggedSubmission() {
            insertEvaluatedSubmissionFor(founder, baselinePipelineId, 90,
                    new BigDecimal("58.00"), null);                                  // intake
            insertEvaluatedSubmissionFor(founder, baselinePipelineId, 45,
                    new BigDecimal("64.00"), null);                                  // check-in
            UUID distanceSubmission = insertEvaluatedSubmissionFor(founder, baselinePipelineId, 10,
                    new BigDecimal("71.00"), distanceTaskId);                        // tagged distance
            insertEvaluatedSubmissionFor(founder, baselinePipelineId, 1,
                    new BigDecimal("99.00"), null);                                  // newer, untagged

            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    distanceSubmission, founder.getId(), Map.of()));

            FounderComparison c = comparisons
                    .findByCohortIdAndUserId(samePairCohortId, founder.getId()).orElseThrow();
            assertThat(c.getDistanceSubmissionId()).isEqualTo(distanceSubmission);
            assertThat(c.getOverallBefore()).isEqualByComparingTo("58.00");
            assertThat(c.getOverallAfter()).isEqualByComparingTo("71.00");
            assertThat(c.getOverallDelta()).isEqualByComparingTo("13.00");
        }

        /**
         * Review #5: the tag on the submission is authoritative — a distance
         * task un-published AFTER members answered it must still compute.
         */
        @Test
        void distanceTaskRevertedToDraft_stillComputesFromTheTag() {
            insertEvaluatedSubmissionFor(founder, baselinePipelineId, 90,
                    new BigDecimal("58.00"), null);
            UUID distanceSubmission = insertEvaluatedSubmissionFor(founder, baselinePipelineId, 10,
                    new BigDecimal("71.00"), distanceTaskId);
            jdbc.update("UPDATE program_tasks SET status = 'DRAFT' WHERE id = ?", distanceTaskId);

            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    distanceSubmission, founder.getId(), Map.of()));

            FounderComparison c = comparisons
                    .findByCohortIdAndUserId(samePairCohortId, founder.getId()).orElseThrow();
            assertThat(c.getDistanceSubmissionId()).isEqualTo(distanceSubmission);
        }

        /**
         * Review #9, the §5 guard's same-pipeline flavor: an equal pair only
         * ever computes through a DISTANCE milestone — with one scheduled the
         * member sees "pending"; without one, "none" (never a teased report).
         */
        @Test
        void equalPair_pendingOnlyWhenADistanceMilestoneExists() {
            assertThat(queryService.myComparison(founder.getId()).state()).isEqualTo("pending");

            User pairless = saveUser("founder.pairless@test.invalid", UserRole.MEMBER, orgA);
            UUID cohortWithoutMilestone = insertCohort(orgA.getId(), "Equal pair, no milestone",
                    pairless.getId());
            jdbc.update("""
                    INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
                    VALUES (?, ?, ?)
                    """, cohortWithoutMilestone, baselinePipelineId, baselinePipelineId);

            MyComparisonResponse my = queryService.myComparison(pairless.getId());
            assertThat(my.state()).isEqualTo("none");
            assertThat(my.cohortId()).isNull();
        }

        /**
         * Without a tagged distance submission an equal pair NEVER falls back
         * to "latest evaluated" — that heuristic cannot tell a check-in from
         * the distance.
         */
        @Test
        void equalPairWithoutATaggedDistance_neverComputes() {
            insertEvaluatedSubmissionFor(founder, baselinePipelineId, 90,
                    new BigDecimal("58.00"), null);
            UUID checkin = insertEvaluatedSubmissionFor(founder, baselinePipelineId, 5,
                    new BigDecimal("64.00"), null);

            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    checkin, founder.getId(), Map.of()));

            assertThat(comparisons.findByCohortIdAndUserId(samePairCohortId, founder.getId()))
                    .isEmpty();
        }

        /**
         * ISSUE-1 / ISSUE-13: the SAME instrument on both sides. The pair's
         * mapping must resolve (every pillar maps to itself — {@code Set.of}
         * on the two equal pipeline ids used to throw "duplicate element",
         * 500ing the mapping read AND swallowing the compute), the comparison
         * must land WITH its pillar rows, and My Growth must leave "pending".
         */
        @Test
        void sameInstrumentPair_mapsPillarsToThemselves_andWritesPillarRows() {
            UUID baselineSubmission = insertEvaluatedSubmissionFor(founder, baselinePipelineId, 90,
                    new BigDecimal("58.00"), null);
            insertPillarEval(baselineSubmission, baselineVision, "50.00", "Emerging");
            insertPillarEval(baselineSubmission, baselineFocus, "60.00", "Strong");
            UUID distanceSubmission = insertEvaluatedSubmissionFor(founder, baselinePipelineId, 10,
                    new BigDecimal("71.00"), distanceTaskId);
            insertPillarEval(distanceSubmission, baselineVision, "62.00", "Strong");
            insertPillarEval(distanceSubmission, baselineFocus, "78.00", "Strong");

            PillarMappingResponse mapping = mappingService.mappingForPair(
                    samePairCohortId, baselinePipelineId, baselinePipelineId);
            assertThat(mapping.baselinePipelineName()).isEqualTo("Baseline FRI");
            assertThat(mapping.distancePipelineName()).isEqualTo("Baseline FRI");
            assertThat(mapping.mappings()).hasSize(3)
                    .allSatisfy(m -> assertThat(m.distancePillarId()).isEqualTo(m.baselinePillarId()));

            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    distanceSubmission, founder.getId(), Map.of()));

            FounderComparison c = comparisons
                    .findByCohortIdAndUserId(samePairCohortId, founder.getId()).orElseThrow();
            assertThat(c.getBaselineSubmissionId()).isEqualTo(baselineSubmission);
            assertThat(c.getDistanceSubmissionId()).isEqualTo(distanceSubmission);

            // Legacy is measured on neither submission → no row; the two
            // evaluated pillars compare against THEMSELVES.
            var rows = comparisonPillars.findByComparisonId(c.getId());
            assertThat(rows).hasSize(2)
                    .allSatisfy(r -> assertThat(r.getState()).isEqualTo(PillarComparisonState.MAPPED));
            var vision = rows.stream()
                    .filter(r -> baselineVision.equals(r.getBaselinePillarId())).findFirst()
                    .orElseThrow();
            assertThat(vision.getDistancePillarId()).isEqualTo(baselineVision);
            assertThat(vision.getBeforePct()).isEqualByComparingTo("50.00");
            assertThat(vision.getAfterPct()).isEqualByComparingTo("62.00");
            assertThat(vision.getDelta()).isEqualByComparingTo("12.00");

            // ISSUE-13: the member's growth page unlocks instead of waiting forever.
            assertThat(queryService.myComparison(founder.getId()).state()).isEqualTo("done");
        }

        /**
         * Recompute deletes-then-rebuilds; when the rebuild cannot happen (the
         * DISTANCE milestone was retyped away, so an equal pair no longer
         * resolves) the stored comparison must be SKIPPED, not wiped — a
         * founder's only computed history is not the price of an admin repair.
         */
        @Test
        void recompute_neverDeletesAComparisonItCannotRebuild() throws Exception {
            UUID baselineSubmission = insertEvaluatedSubmissionFor(founder, baselinePipelineId, 90,
                    new BigDecimal("58.00"), null);
            insertPillarEval(baselineSubmission, baselineVision, "50.00", "Emerging");
            UUID distanceSubmission = insertEvaluatedSubmissionFor(founder, baselinePipelineId, 10,
                    new BigDecimal("71.00"), distanceTaskId);
            insertPillarEval(distanceSubmission, baselineVision, "62.00", "Strong");
            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    distanceSubmission, founder.getId(), Map.of()));
            FounderComparison stored = comparisons
                    .findByCohortIdAndUserId(samePairCohortId, founder.getId()).orElseThrow();

            // The distance milestone is retyped away: the equal pair no longer
            // resolves (equalPairWithoutATaggedDistance_neverComputes semantics).
            jdbc.update("UPDATE program_tasks SET milestone_role = NULL WHERE id = ?", distanceTaskId);

            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            mockMvc.perform(post("/api/admin/comparisons/cohorts/" + samePairCohortId + "/recompute"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recomputed", is(0)));

            FounderComparison after = comparisons
                    .findByCohortIdAndUserId(samePairCohortId, founder.getId()).orElseThrow();
            assertThat(after.getId()).isEqualTo(stored.getId());
            assertThat(comparisonPillars.findByComparisonId(after.getId())).isNotEmpty();
        }

        private UUID insertEvaluatedSubmissionFor(User user, UUID pipelineId, int daysAgo,
                                                  BigDecimal overallScore, UUID programTaskId) {
            UUID assignmentId = jdbc
                    .queryForList("SELECT id FROM assignments WHERE user_id = ? AND pipeline_id = ?",
                            UUID.class, user.getId(), pipelineId)
                    .stream().findFirst().orElseGet(() -> {
                        UUID id = UUID.randomUUID();
                        jdbc.update("""
                                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                                VALUES (?, ?, ?, ?, ?)
                                """, id, pipelineId, orgA.getId(), user.getId(), user.getId());
                        return id;
                    });
            UUID submissionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO submissions (id, assignment_id, user_id, status, program_task_id,
                                             submitted_at, evaluated_at, created_at)
                    VALUES (?, ?, ?, 'EVALUATED', ?,
                            now() - make_interval(days => ?), now() - make_interval(days => ?),
                            now() - make_interval(days => ?))
                    """, submissionId, assignmentId, user.getId(), programTaskId,
                    daysAgo, daysAgo, daysAgo);
            jdbc.update("""
                    INSERT INTO overall_summaries (submission_id, overall_score_percentage)
                    VALUES (?, ?)
                    """, submissionId, overallScore);
            return submissionId;
        }
    }

    /* ------------------------------------------------------------ mapping */

    @Nested
    class Mapping {

        @Test
        void firstRead_seedsByCaseInsensitiveNameMatch() {
            PillarMappingResponse mapping =
                    mappingService.mappingForPair(cohortId, baselinePipelineId, distancePipelineId);

            assertThat(mapping.mappings()).hasSize(4);
            var visionRow = row(mapping, baselineVision);
            assertThat(visionRow.distancePillarId()).isEqualTo(distanceVision);
            assertThat(visionRow.source()).isEqualTo(MappingSource.AUTO.name());
            assertThat(row(mapping, baselineLegacy).distancePillarId()).isNull();
            assertThat(mapping.mappings().stream()
                    .filter(m -> m.baselinePillarId() == null)
                    .map(PillarMappingResponse.MappingRow::distancePillarId))
                    .containsExactly(distanceResilience);
        }

        @Test
        void unmapThenRemap_keepsEveryPillarAccountedFor() {
            PillarMappingResponse seeded =
                    mappingService.mappingForPair(cohortId, baselinePipelineId, distancePipelineId);
            UUID actor = UUID.randomUUID();

            PillarMappingResponse afterUnmap =
                    mappingService.unmap(row(seeded, baselineVision).id(), actor);
            assertThat(row(afterUnmap, baselineVision).distancePillarId()).isNull();
            assertThat(row(afterUnmap, baselineVision).source())
                    .isEqualTo(MappingSource.MANUAL.name());
            // The freed distance pillar shows up as newly measured.
            assertThat(afterUnmap.mappings().stream()
                    .filter(m -> m.baselinePillarId() == null)
                    .map(PillarMappingResponse.MappingRow::distancePillarId))
                    .containsExactlyInAnyOrder(distanceVision, distanceResilience);

            // Name-mismatched manual mapping: Legacy → Resilience.
            PillarMappingResponse afterMap = mappingService.map(cohortId, baselinePipelineId,
                    distancePipelineId, baselineLegacy, distanceResilience, actor);
            var legacyRow = row(afterMap, baselineLegacy);
            assertThat(legacyRow.distancePillarId()).isEqualTo(distanceResilience);
            assertThat(legacyRow.source()).isEqualTo(MappingSource.MANUAL.name());
            // No pillar vanished: Resilience's old one-sided row is gone, Vision's remains.
            assertThat(afterMap.mappings().stream()
                    .filter(m -> m.baselinePillarId() == null)
                    .map(PillarMappingResponse.MappingRow::distancePillarId))
                    .containsExactly(distanceVision);
        }

        private PillarMappingResponse.MappingRow row(PillarMappingResponse mapping, UUID baselinePillarId) {
            return mapping.mappings().stream()
                    .filter(m -> baselinePillarId.equals(m.baselinePillarId()))
                    .findFirst().orElseThrow();
        }
    }

    /* ---------------------------------------------------------- recompute */

    @Nested
    class Recompute {

        @Test
        void recompute_appliesCurrentConfig_andIsSuperAdminOnly() throws Exception {
            computeFounder1();
            assertThat(comparisons.findByCohortIdAndUserId(cohortId, founder1.getId())
                    .orElseThrow().getOverallBandLabel()).isEqualTo("Moderate");

            // Config edit applies FORWARD only — the stored label is unchanged
            // until the explicit recompute restates it.
            jdbc.update("""
                    INSERT INTO platform_settings (key, value_text, updated_at)
                    VALUES ('scoring.shift_bands',
                            '{"bands":[{"key":"decline","label":"Decline","min":null,"max":-1},{"key":"band_1","label":"Solid","min":0,"max":null}]}',
                            now())
                    """);
            assertThat(comparisons.findByCohortIdAndUserId(cohortId, founder1.getId())
                    .orElseThrow().getOverallBandLabel()).isEqualTo("Moderate");

            TestAuthentication.authenticate(founder1);
            mockMvc.perform(post("/api/admin/comparisons/cohorts/" + cohortId + "/recompute"))
                    .andExpect(status().isForbidden());

            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            mockMvc.perform(post("/api/admin/comparisons/cohorts/" + cohortId + "/recompute"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recomputed", is(1)));

            FounderComparison after = comparisons
                    .findByCohortIdAndUserId(cohortId, founder1.getId()).orElseThrow();
            assertThat(after.getOverallBandLabel()).isEqualTo("Solid");
            assertThat(after.getOverallBandKey()).isEqualTo("band_1");
        }
    }

    /* ------------------------------------------- not-computed visibility */

    /**
     * ISSUE-1/ISSUE-13: a compute that throws is swallowed so the member's
     * evaluation survives — which used to make the stuck state invisible. The
     * state is derived, not stored: enrolled + both sides evaluated + no
     * {@code founder_comparisons} row.
     */
    @Nested
    class NotComputedVisibility {

        @Test
        void countsOnlyFoundersReadyButUncomputed() throws Exception {
            // founder1 has both sides evaluated and no row yet; founder2 has a
            // baseline only, so they are not waiting on anything.
            var before = queryService.cohortComparisonStatus(orgA.getId(), cohortId);
            assertThat(before.notComputed()).isEqualTo(1);
            assertThat(before.founders()).singleElement()
                    .extracting(CohortComparisonStatus.PendingFounder::userId)
                    .isEqualTo(founder1.getId());

            computeFounder1();

            assertThat(queryService.cohortComparisonStatus(orgA.getId(), cohortId).notComputed())
                    .as("a founder with a stored comparison is not counted")
                    .isZero();
        }

        @Test
        void undesignatedCohort_reportsNothing() {
            UUID pairless = insertCohort(orgA.getId(), "Pairless", founder1.getId());
            assertThat(queryService.cohortComparisonStatus(orgA.getId(), pairless).notComputed())
                    .isZero();
        }

        @Test
        void orgAdminSeesTheCountOnTheirCohort() throws Exception {
            TestAuthentication.authenticate(
                    saveUser("orgadmin.status@test.invalid", UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparison-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notComputed", is(1)))
                    .andExpect(jsonPath("$.founders[0].userId", is(founder1.getId().toString())));
        }

        @Test
        void orgAdminRecomputesOwnCohort_butNotAnotherOrgs() throws Exception {
            TestAuthentication.authenticate(
                    saveUser("orgadmin.recompute@test.invalid", UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(post("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons/recompute"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recomputed", is(1)));
            assertThat(comparisons.findByCohortIdAndUserId(cohortId, founder1.getId()))
                    .as("the repair actually lands the row the member was waiting for")
                    .isPresent();

            // Another org's admin is refused at the org gate...
            TestAuthentication.authenticate(
                    saveUser("orgadmin.b.recompute@test.invalid", UserRole.ORG_ADMIN, orgB));
            mockMvc.perform(post("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons/recompute"))
                    .andExpect(status().isForbidden());

            // ...and org A's cohort smuggled under their OWN org id is a 404.
            mockMvc.perform(post("/api/organizations/" + orgB.getId()
                            + "/cohorts/" + cohortId + "/comparisons/recompute"))
                    .andExpect(status().isNotFound());
        }
    }

    /* ------------------------------------- platform cohorts: org scoping */

    /**
     * A PLATFORM cohort (spec §13) can span orgs, but everything an ORG admin
     * does on it must stay inside their tenant: the status read must not name
     * another org's founders, and their recompute must not rebuild another
     * org's comparisons or return another org's approved narratives to draft.
     */
    @Nested
    class PlatformCohortOrgScoping {

        private User founderB;
        private UUID founderBDistanceSubmission;

        @BeforeEach
        void enrollAnOrgBFounderInTheSameCohort() {
            jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                    cohortId, orgB.getId());
            founderB = saveUser("founder.b@test.invalid", UserRole.MEMBER, orgB);
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    cohortId, founderB.getId());
            // Both sides evaluated — founderB is exactly as computable as founder1.
            insertEvaluatedSubmission(founderB, baselinePipelineId, 40, new BigDecimal("50.00"));
            founderBDistanceSubmission = insertEvaluatedSubmission(founderB, distancePipelineId, 0,
                    new BigDecimal("60.00"));
        }

        @Test
        void statusReadNeverNamesAnotherOrgsFounders() {
            // Org A's view: only their own awaiting founder, even though founderB
            // is just as ready — their id and name belong to org B's admin.
            assertThat(queryService.cohortComparisonStatus(orgA.getId(), cohortId).founders())
                    .extracting(CohortComparisonStatus.PendingFounder::userId)
                    .containsExactly(founder1.getId());

            // The platform (super admin) variant is the one that spans orgs.
            assertThat(queryService.cohortComparisonStatusPlatform(cohortId).founders())
                    .extracting(CohortComparisonStatus.PendingFounder::userId)
                    .containsExactlyInAnyOrder(founder1.getId(), founderB.getId());
        }

        @Test
        void orgAdminRecompute_neverRebuildsOrUnapprovesAnotherOrgsRows() throws Exception {
            // founderB already has a computed comparison and an APPROVED narrative.
            computeService.onSubmissionEvaluated(new EvaluationEvents.SubmissionEvaluated(
                    founderBDistanceSubmission, founderB.getId(), Map.of()));
            FounderComparison orgBRow = comparisons
                    .findByCohortIdAndUserId(cohortId, founderB.getId()).orElseThrow();
            jdbc.update("""
                    INSERT INTO shift_narratives (cohort_id, org_id, user_id, baseline_pillar_id,
                                                  distance_pillar_id, pillar_name_snapshot, kind,
                                                  body, status, approved_by, approved_at)
                    VALUES (?, ?, ?, ?, ?, 'Vision', 'PERSISTED', 'Org B narrative.',
                            'APPROVED', ?, now())
                    """, cohortId, orgB.getId(), founderB.getId(), baselineVision, distanceVision,
                    founderB.getId());

            TestAuthentication.authenticate(
                    saveUser("orgadmin.a.scope@test.invalid", UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(post("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons/recompute"))
                    .andExpect(status().isOk())
                    // founder1 only — founderB's rebuild belongs to org B or the platform.
                    .andExpect(jsonPath("$.recomputed", is(1)));

            // Org A's repair landed...
            assertThat(comparisons.findByCohortIdAndUserId(cohortId, founder1.getId()))
                    .isPresent();
            // ...and org B's comparison was not rebuilt: same row, same stamp.
            FounderComparison after = comparisons
                    .findByCohortIdAndUserId(cohortId, founderB.getId()).orElseThrow();
            assertThat(after.getId()).isEqualTo(orgBRow.getId());
            // Tolerance, not truncation: the in-memory Instant carries nanos,
            // timestamptz ROUNDS to micros on the fresh DB read the recompute's
            // clearing modifying queries force — truncating both sides still
            // flakes by 1µs whenever the nano fraction rounds up.
            assertThat(after.getComputedAt())
                    .isCloseTo(orgBRow.getComputedAt(),
                            org.assertj.core.api.Assertions.within(1, ChronoUnit.MICROS));
            // Org B's approved narrative survived with its approval intact —
            // another tenant's recompute must not send it back to review.
            Map<String, Object> narrative = jdbc.queryForMap("""
                    SELECT status, approved_by, approved_at FROM shift_narratives
                    WHERE cohort_id = ? AND user_id = ?
                    """, cohortId, founderB.getId());
            assertThat(narrative.get("status")).isEqualTo("APPROVED");
            assertThat(narrative.get("approved_by")).isEqualTo(founderB.getId());
            assertThat(narrative.get("approved_at")).isNotNull();
        }
    }

    /* --------------------------------------------------------------- authz */

    @Nested
    class Tenancy {

        @Test
        void memberReadsOwnComparisonOnly() throws Exception {
            computeFounder1();

            TestAuthentication.authenticate(founder1);
            mockMvc.perform(get("/api/my/growth/comparison"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is("done")))
                    .andExpect(jsonPath("$.comparison.userId", is(founder1.getId().toString())))
                    .andExpect(jsonPath("$.comparison.overallBandLabel", is("Moderate")));

            // Same cohort, same endpoint — founder2 gets THEIR state, never
            // founder1's computed comparison.
            TestAuthentication.authenticate(founder2);
            mockMvc.perform(get("/api/my/growth/comparison"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is("pending")))
                    .andExpect(jsonPath("$.comparison").doesNotExist());
        }

        @Test
        void foreignOrgAdmin_cannotListComparisons() throws Exception {
            computeFounder1();
            TestAuthentication.authenticate(
                    saveUser("orgadmin.b@test.invalid", UserRole.ORG_ADMIN, orgB));
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void orgAdmin_listsAndReadsPerFounder() throws Exception {
            computeFounder1();
            TestAuthentication.authenticate(
                    saveUser("orgadmin.a@test.invalid", UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].userId", is(founder1.getId().toString())));
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons/" + founder1.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pillars.length()", is(4)))
                    .andExpect(jsonPath("$.baselineEvaluatedAt").exists());
            // Read-only mapping view for the designated pair.
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparison-mapping"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mappings.length()", is(4)));
        }

        /**
         * "No computed pair yet" is an EXPECTED state on the cohort member
         * screen and the settings tab, so both reads answer 204, never a 404
         * for the browser console to log. 404 stays for the genuine cases: a
         * user outside the cohort's org slice.
         */
        @Test
        void orgAdmin_absentComparisonAndMapping_are204_notFoundStaysForOutsiders() throws Exception {
            TestAuthentication.authenticate(
                    saveUser("orgadmin.a.204@test.invalid", UserRole.ORG_ADMIN, orgA));

            // founder2 is enrolled but has no comparison yet → 204, empty body.
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons/" + founder2.getId()))
                    .andExpect(status().isNoContent());

            // A user outside the cohort's org slice is a genuine 404.
            User outsider = saveUser("outsider.204@test.invalid", UserRole.MEMBER, orgB);
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/comparisons/" + outsider.getId()))
                    .andExpect(status().isNotFound());

            // A cohort with no designated pair → the mapping read is 204.
            UUID pairless = insertCohort(orgA.getId(), "Pairless cohort", founder2.getId());
            mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + pairless + "/comparison-mapping"))
                    .andExpect(status().isNoContent());
        }

        @Test
        void coachRead_isGatedByTheAssignmentUnion() throws Exception {
            computeFounder1();
            User coach = saveUser("coach.a@test.invalid", UserRole.COACH, orgA);

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId() + "/comparison"))
                    .andExpect(status().isNotFound());

            jdbc.update("""
                    INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                    VALUES (?, ?, ?, ?)
                    """, orgA.getId(), coach.getId(), cohortId, null);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId() + "/comparison"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is("done")))
                    .andExpect(jsonPath("$.comparison.overallBandLabel", is("Moderate")));
        }
    }

    /* -------------------------------------------------------- staff exports */

    /**
     * Spec §11: "the founder profile Growth tab gets the same PDF/Excel
     * exports". Both staff doors render the SAME renderer the member's own
     * export uses, so the three surfaces can never disagree; what differs is
     * only who is allowed to ask.
     */
    @Nested
    class StaffGrowthExports {

        private static final String XLSX =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

        @Test
        void orgAdminDownloadsBothFormats_forAMemberOfTheirOrg() throws Exception {
            computeFounder1();
            makePremium(orgA);
            TestAuthentication.authenticate(
                    saveUser("orgadmin.export@test.invalid", UserRole.ORG_ADMIN, orgA));

            mockMvc.perform(get(adminReport("growth-report.pdf", founder1)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition",
                            containsString("Growth_Report.pdf")));

            mockMvc.perform(get(adminReport("growth-report.xlsx", founder1)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(XLSX));
        }

        /** The comparison read and the export must agree on who exists. */
        @Test
        void foreignAdminIsRefused_andAForeignMemberIs404() throws Exception {
            computeFounder1();
            makePremium(orgA);

            TestAuthentication.authenticate(
                    saveUser("orgadmin.export.b@test.invalid", UserRole.ORG_ADMIN, orgB));
            mockMvc.perform(get(adminReport("growth-report.pdf", founder1)))
                    .andExpect(status().isForbidden());

            User outsider = saveUser("outsider.export@test.invalid", UserRole.MEMBER, orgB);
            TestAuthentication.authenticate(
                    saveUser("orgadmin.export.a@test.invalid", UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(get(adminReport("growth-report.pdf", outsider)))
                    .andExpect(status().isNotFound());
        }

        /** Same plan gate as the member's own export — the org's tier entitles it. */
        @Test
        void freeTierOrgIsRefusedThePdf() throws Exception {
            computeFounder1();
            TestAuthentication.authenticate(
                    saveUser("orgadmin.export.free@test.invalid", UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(get(adminReport("growth-report.pdf", founder1)))
                    .andExpect(status().isForbidden());
        }

        /** A member has no business on a STAFF door, even for their own report. */
        @Test
        void theFounderThemselvesCannotUseTheStaffDoors() throws Exception {
            computeFounder1();
            makePremium(orgA);

            TestAuthentication.authenticate(founder1);
            mockMvc.perform(get(adminReport("growth-report.pdf", founder1)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId()
                            + "/growth-report.pdf"))
                    .andExpect(status().isForbidden());
        }

        /** The coach door obeys the same assignment union as the comparison read. */
        @Test
        void coachExportIsGatedByTheAssignmentUnion() throws Exception {
            computeFounder1();
            makePremium(orgA);
            User coach = saveUser("coach.export@test.invalid", UserRole.COACH, orgA);

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId()
                            + "/growth-report.pdf"))
                    .andExpect(status().isNotFound());

            jdbc.update("""
                    INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                    VALUES (?, ?, ?, ?)
                    """, orgA.getId(), coach.getId(), cohortId, null);

            mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId()
                            + "/growth-report.pdf"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF));
            mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId()
                            + "/growth-report.xlsx"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(XLSX));
        }

        /**
         * The staff doors are MASKED BY DEFAULT — the founder appears as
         * "Member" on the document and in the filename, same convention as
         * every other org-scoped export. Asserted on the bytes, because a
         * mocked service proves nothing about what the admin actually holds.
         */
        @Test
        void staffExportsAreMaskedByDefault() throws Exception {
            computeFounder1();
            makePremium(orgA);
            TestAuthentication.authenticate(
                    saveUser("orgadmin.masked@test.invalid", UserRole.ORG_ADMIN, orgA));

            var pdf = mockMvc.perform(get(adminReport("growth-report.pdf", founder1)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            containsString("Member_Growth_Report.pdf")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(pdfText(pdf)).doesNotContain(founder1.getName()).contains("Member");

            var xlsx = mockMvc.perform(get(adminReport("growth-report.xlsx", founder1)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            containsString("Member_Growth_Report.xlsx")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(cellText(xlsx)).doesNotContain(founder1.getName());
        }

        /**
         * An ORG_ADMIN may unmask their own org's members ({@code ExportNameGuard},
         * operator ruling 2026-08-14) — and the name lands in the document and the
         * filename, not just a 200.
         */
        @Test
        void orgAdminAskingForNamesGetsTheRealName() throws Exception {
            computeFounder1();
            makePremium(orgA);
            TestAuthentication.authenticate(
                    saveUser("orgadmin.asknames@test.invalid", UserRole.ORG_ADMIN, orgA));

            // "founder.one" sanitises to "founderone" in the filename.
            var pdf = mockMvc.perform(get(adminReport("growth-report.pdf", founder1)
                            + "?showNames=true"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            containsString("founderone_Growth_Report.pdf")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(pdfText(pdf)).contains(founder1.getName());

            var xlsx = mockMvc.perform(get(adminReport("growth-report.xlsx", founder1)
                            + "?showNames=true"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(cellText(xlsx)).contains(founder1.getName());
        }

        @Test
        void superAdminAskingForNamesGetsTheRealName() throws Exception {
            computeFounder1();
            makePremium(orgA);
            TestAuthentication.authenticateAsSuperAdmin(userRepository);

            // "founder.one" sanitises to "founderone" in the filename.
            var pdf = mockMvc.perform(get(adminReport("growth-report.pdf", founder1)
                            + "?showNames=true"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            containsString("founderone_Growth_Report.pdf")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(pdfText(pdf)).contains(founder1.getName());

            var xlsx = mockMvc.perform(get(adminReport("growth-report.xlsx", founder1)
                            + "?showNames=true"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(cellText(xlsx)).contains(founder1.getName());
        }

        /**
         * The coach door is ALWAYS masked: an explicit {@code showNames=true}
         * is refused loudly (COACH is on neither side of the guard's
         * SUPER_ADMIN-or-ORG_ADMIN allowlist), and the default
         * document carries the masked label, not the founder's name.
         */
        @Test
        void coachExportIsAlwaysMasked() throws Exception {
            computeFounder1();
            makePremium(orgA);
            User coach = saveUser("coach.masked@test.invalid", UserRole.COACH, orgA);
            jdbc.update("""
                    INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                    VALUES (?, ?, ?, ?)
                    """, orgA.getId(), coach.getId(), cohortId, null);
            TestAuthentication.authenticate(coach);

            mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId()
                            + "/growth-report.pdf?showNames=true"))
                    .andExpect(status().isForbidden());

            var pdf = mockMvc.perform(get("/api/v1/coach/founders/" + founder1.getId()
                            + "/growth-report.pdf"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            containsString("Member_Growth_Report.pdf")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(pdfText(pdf)).doesNotContain(founder1.getName());
        }

        /**
         * The staff doors render the STAFF voice (third person): this is a
         * document ABOUT the member, generated by somebody else, so it must not
         * address its reader as its subject. The member's own door keeps the
         * member voice verbatim.
         */
        @Test
        void staffDoorsSpeakInTheThirdPerson_theMemberDoorKeepsTheMemberVoice() throws Exception {
            computeFounder1();
            makePremium(orgA);
            TestAuthentication.authenticate(
                    saveUser("orgadmin.voice@test.invalid", UserRole.ORG_ADMIN, orgA));

            String staff = pdfText(mockMvc.perform(get(adminReport("growth-report.pdf", founder1)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray());
            assertThat(staff).contains("Where they were").doesNotContain("Where you were");
            assertThat(staff).contains("Trajectory").doesNotContain("Your trajectory");

            TestAuthentication.authenticate(founder1);
            String mine = pdfText(mockMvc.perform(get("/api/my/growth/report/pdf"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray());
            assertThat(mine).contains("Where you were").contains("Your trajectory");
        }

        /**
         * The {@code tz} param renders every date in the READER's zone, so the
         * PDF agrees with the browser-local screen (live QA: "May 21" on paper,
         * "May 22" on screen). Garbage falls back to UTC — a mistyped zone must
         * not cost anyone the report.
         */
        @Test
        void tzParamRendersDatesInTheReadersZone_garbageFallsBackToUtc() throws Exception {
            computeFounder1();
            makePremium(orgA);
            // Just before UTC midnight: still May 21 in UTC, already May 22 in
            // Auckland.
            jdbc.update("UPDATE submissions SET evaluated_at = '2026-05-21 23:30:00+00' WHERE id = ?",
                    founder1DistanceSubmission);
            TestAuthentication.authenticate(
                    saveUser("orgadmin.tz@test.invalid", UserRole.ORG_ADMIN, orgA));

            String auckland = pdfText(mockMvc.perform(
                            get(adminReport("growth-report.pdf", founder1) + "?tz=Pacific/Auckland"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray());
            assertThat(auckland).contains("May 22, 2026").doesNotContain("May 21, 2026");

            String garbage = pdfText(mockMvc.perform(
                            get(adminReport("growth-report.pdf", founder1) + "?tz=not-a-zone"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray());
            assertThat(garbage).contains("May 21, 2026");
        }

        /**
         * The narrative sheet is WIDE: one row per pillar, one column per
         * kind. The long shape repeated the pillar name and the per-pillar
         * closing action once per observation, so a multi-kind breakdown read
         * as near-duplicate rows.
         */
        @Test
        void narrativeSheet_isOneRowPerPillar_withAKindPerColumn() throws Exception {
            computeFounder1();
            makePremium(orgA);
            jdbc.update("""
                    INSERT INTO shift_narratives (cohort_id, org_id, user_id, baseline_pillar_id,
                                                  distance_pillar_id, pillar_name_snapshot,
                                                  items, closing_action, status, approved_at)
                    VALUES (?, ?, ?, ?, ?, 'Vision', ?::jsonb, 'Close one open decision.',
                            'APPROVED', now())
                    """, cohortId, orgA.getId(), founder1.getId(), baselineVision, distanceVision,
                    """
                    [{"kind":"RESOLVED","text":"The hesitation is gone."},
                     {"kind":"RESOLVED","text":"So is the second one."},
                     {"kind":"REGRESSED","text":"The weekly review is now an edge."},
                     {"kind":"FADED","text":"The exploratory streak is absent."}]
                    """);
            TestAuthentication.authenticate(
                    saveUser("orgadmin.wide@test.invalid", UserRole.ORG_ADMIN, orgA));

            byte[] xlsx = mockMvc.perform(get(adminReport("growth-report.xlsx", founder1)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                    new java.io.ByteArrayInputStream(xlsx))) {
                var sheet = wb.getSheet("Shift narratives");
                assertThat(sheet).isNotNull();
                var header = sheet.getRow(0);
                assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Pillar");
                // The pillar's numbers ride beside the prose, printed by code
                // so the figures are always the deterministic ones.
                assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Before → after");
                // Columns run in the reading arc — gained, resolved, held, still
                // open, newly picked up, slipped, lost — and each header states the
                // before → after move it stands for, so "Carried forward" needs no
                // glossary.
                assertThat(header.getCell(2).getStringCellValue())
                        .isEqualTo("New (not present → strength)");
                assertThat(header.getCell(3).getStringCellValue())
                        // Two outcomes, not one — RESOLVED covers a growth edge
                        // that became a strength AND one that simply stopped
                        // appearing. Naming only the first overstates it.
                        // Strength-only since V203 — absence is not resolution.
                        .isEqualTo("Resolved (growth edge → strength)");
                assertThat(header.getCell(4).getStringCellValue())
                        // No arrow where nothing moved — an arrow in this sheet
                        // now always means the state actually changed.
                        .isEqualTo("Carried forward (strength · held)");
                // V202's two negative cells. Both MOVE, so both keep an arrow —
                // and REGRESSED is what the mis-filed FADED rows really were.
                assertThat(header.getCell(6).getStringCellValue())
                        .isEqualTo("Emerged (not present → growth edge)");
                assertThat(header.getCell(7).getStringCellValue())
                        .isEqualTo("Regressed (strength → growth edge)");
                assertThat(header.getCell(8).getStringCellValue())
                        .isEqualTo("Faded (strength → not present)");
                assertThat(header.getCell(9).getStringCellValue()).isEqualTo("Next step");
                // The pillar appears ONCE despite carrying three observations.
                assertThat(sheet.getLastRowNum()).isEqualTo(1);
                var row = sheet.getRow(1);
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Vision");
                assertThat(row.getCell(1).getStringCellValue()).contains("→");
                assertThat(row.getCell(2).getStringCellValue()).isEmpty();
                // Two observations of one kind share their cell, as bullets.
                assertThat(row.getCell(3).getStringCellValue())
                        .contains("• The hesitation is gone.")
                        .contains("• So is the second one.");
                assertThat(row.getCell(4).getStringCellValue()).isEmpty();
                assertThat(row.getCell(6).getStringCellValue()).isEmpty();
                assertThat(row.getCell(7).getStringCellValue())
                        .isEqualTo("• The weekly review is now an edge.");
                assertThat(row.getCell(8).getStringCellValue())
                        .isEqualTo("• The exploratory streak is absent.");
                assertThat(row.getCell(9).getStringCellValue())
                        .isEqualTo("Close one open decision.");
            }
        }

        /** The rendered PDF text — the content stream is FlateDecoded, so raw bytes see nothing. */
        private static String pdfText(byte[] pdf) throws Exception {
            com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
            try {
                var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
                StringBuilder text = new StringBuilder();
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    text.append(extractor.getTextFromPage(page)).append('\n');
                }
                return text.toString();
            } finally {
                reader.close();
            }
        }

        /** Every string cell in the workbook — the readable content of the export. */
        private static String cellText(byte[] xlsx) throws Exception {
            StringBuilder out = new StringBuilder();
            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                    new java.io.ByteArrayInputStream(xlsx))) {
                for (var sheet : wb) {
                    for (var row : sheet) {
                        for (var cell : row) {
                            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                out.append(cell.getStringCellValue()).append('\n');
                            }
                        }
                    }
                }
            }
            return out.toString();
        }

        private String adminReport(String file, User member) {
            return "/api/organizations/" + orgA.getId() + "/members/"
                    + member.getId() + "/" + file;
        }

        private void makePremium(Organization org) {
            org.setSubscriptionTier(SubscriptionTier.GROWTH);
            organizationRepository.saveAndFlush(org);
        }
    }

    /* ------------------------------------------------ cohort growth rollup */

    /**
     * The cohort per-pillar aggregate + its Excel export (operator-settled
     * dashboard work 2026-08-16): averages over MAPPED rows only, ranking by
     * average shift, approved-and-attached-only kind counts, and the same
     * premium gate as the other file exports.
     */
    @Nested
    class CohortGrowthAggregate {

        @Autowired private com.bvisionry.comparison.web.CohortGrowthAggregateService aggregates;

        private static final String XLSX =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

        private String aggregateUrl() {
            return "/api/organizations/" + orgA.getId() + "/cohorts/" + cohortId
                    + "/growth-aggregate";
        }

        @Test
        void ranksMappedPillarAveragesByShift_andSkipsOneSidedRows() throws Exception {
            computeFounder1();
            TestAuthentication.authenticate(
                    saveUser("orgadmin.agg@test.invalid", UserRole.ORG_ADMIN, orgA));

            mockMvc.perform(get(aggregateUrl()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.membersMeasured", is(1)))
                    .andExpect(jsonPath("$.avgOverallBefore", is(58.0)))
                    .andExpect(jsonPath("$.avgOverallAfter", is(71.5)))
                    .andExpect(jsonPath("$.avgOverallDelta", is(13.5)))
                    // Legacy (not re-measured) and Resilience (newly measured)
                    // have no before AND after — out of the rollup entirely.
                    .andExpect(jsonPath("$.pillars.length()", is(2)))
                    // Ranked by average shift: Focus +18 before Vision +12.
                    .andExpect(jsonPath("$.pillars[0].pillarName", is("Focus & Flow")))
                    .andExpect(jsonPath("$.pillars[0].avgBefore", is(60.0)))
                    .andExpect(jsonPath("$.pillars[0].avgAfter", is(78.0)))
                    .andExpect(jsonPath("$.pillars[0].avgDelta", is(18.0)))
                    .andExpect(jsonPath("$.pillars[0].measuredCount", is(1)))
                    .andExpect(jsonPath("$.pillars[1].avgDelta", is(12.0)))
                    .andExpect(jsonPath("$.pillars[1].distancePillarId",
                            is(distanceVision.toString())));
        }

        /**
         * O2: "N of M moved Formative → Strong in this pillar". Only rows with
         * BOTH snapshots contribute — a one-sided pillar has no transition to
         * report — and the held bucket sorts last so the movers read first.
         */
        @Test
        void bandMoves_countTransitionsPerPillar_heldLast() {
            computeFounder1();

            var dto = aggregates.aggregate(orgA.getId(), cohortId);
            var vision = dto.pillars().stream()
                    .filter(p -> p.distancePillarId().equals(distanceVision))
                    .findFirst().orElseThrow();

            // One founder computed, so exactly one transition on the mapped
            // pillar, and its members must total the banded population.
            assertThat(vision.bandMoves()).isNotEmpty();
            assertThat(vision.bandMoves().stream().mapToInt(m -> m.members()).sum())
                    .isEqualTo(1);
            assertThat(vision.bandMoves())
                    .allSatisfy(m -> {
                        assertThat(m.from()).isNotBlank();
                        assertThat(m.to()).isNotBlank();
                        assertThat(m.held()).isEqualTo(m.from().equals(m.to()));
                    });
            // Held never outranks a real move.
            assertThat(vision.bandMoves().stream().map(m -> m.held()).toList())
                    .isSortedAccordingTo(java.util.Comparator.naturalOrder());

            // A pillar measured on one side only reports no transition at all.
            assertThat(dto.pillars().stream()
                    .filter(p -> p.distancePillarId().equals(distanceResilience))
                    .findFirst())
                    .isEmpty();
        }

        @Test
        void kindCounts_areApprovedAndStillAttachedOnly_distinctPerMember() {
            computeFounder1();

            // APPROVED breakdown on the mapped Vision pillar: RESOLVED twice
            // (must count the member once, not twice) + NEW.
            insertNarrative(distanceVision, "vision", "APPROVED", """
                    [{"kind":"RESOLVED","text":"a"},{"kind":"RESOLVED","text":"b"},
                     {"kind":"NEW","text":"c"}]
                    """);
            // DRAFT on the mapped Focus pillar: unreviewed AI output must never
            // reach an admin-facing count.
            insertNarrative(distanceFocus, "Focus & Flow", "DRAFT",
                    "[{\"kind\":\"FADED\",\"text\":\"d\"}]");
            // APPROVED on the NEWLY_MEASURED Resilience pillar: detached (no
            // mapped before/after), so it must not resurface here.
            insertNarrative(distanceResilience, "Resilience", "APPROVED",
                    "[{\"kind\":\"NEW\",\"text\":\"e\"}]");

            var dto = aggregates.aggregate(orgA.getId(), cohortId);

            var vision = dto.pillars().stream()
                    .filter(p -> p.distancePillarId().equals(distanceVision)).findFirst().orElseThrow();
            assertThat(vision.membersWithApprovedNarrative()).isEqualTo(1);
            assertThat(vision.kindCounts())
                    .containsEntry(com.bvisionry.comparison.domain.NarrativeKind.RESOLVED, 1)
                    .containsEntry(com.bvisionry.comparison.domain.NarrativeKind.NEW, 1)
                    .containsEntry(com.bvisionry.comparison.domain.NarrativeKind.FADED, 0);

            var focus = dto.pillars().stream()
                    .filter(p -> p.distancePillarId().equals(distanceFocus)).findFirst().orElseThrow();
            assertThat(focus.membersWithApprovedNarrative()).isZero();
            assertThat(focus.kindCounts().values()).allMatch(c -> c == 0);

            assertThat(dto.pillars()).noneMatch(
                    p -> p.distancePillarId().equals(distanceResilience));
        }

        @Test
        void xlsxExport_isPremiumGated_andDownloads() throws Exception {
            computeFounder1();
            TestAuthentication.authenticate(
                    saveUser("orgadmin.agg.export@test.invalid", UserRole.ORG_ADMIN, orgA));
            String url = "/api/organizations/" + orgA.getId() + "/cohorts/" + cohortId
                    + "/growth-report.xlsx";

            mockMvc.perform(get(url)).andExpect(status().isForbidden());

            orgA.setSubscriptionTier(SubscriptionTier.GROWTH);
            organizationRepository.saveAndFlush(orgA);
            byte[] xlsx = mockMvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(XLSX))
                    .andExpect(header().string("Content-Disposition",
                            containsString("Cohort_Growth_Report")))
                    .andReturn().getResponse().getContentAsByteArray();

            // One tag column per kind, read off the enum: the aggregate counts
            // every kind, so a hand-kept header list here would silently drop
            // any kind added later (V202 added two).
            //
            // Ordered by the READING ARC, not by enum order, so this sheet and
            // the member growth sheet put the same kind in the same place — an
            // admin reads both.
            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                    new java.io.ByteArrayInputStream(xlsx))) {
                var header = wb.getSheet("Narrative tags").getRow(0);
                assertThat((int) header.getLastCellNum())
                        .isEqualTo(2 + com.bvisionry.comparison.domain.NarrativeKind.values().length);
                assertThat(header.getCell(2).getStringCellValue()).isEqualTo("New");
                assertThat(header.getCell(6).getStringCellValue()).isEqualTo("Emerged");
                assertThat(header.getCell(7).getStringCellValue()).isEqualTo("Regressed");
                assertThat(header.getCell(8).getStringCellValue()).isEqualTo("Faded");
            }
        }

        @Test
        void foreignOrgAdmin_isRefusedTheAggregate() throws Exception {
            computeFounder1();
            TestAuthentication.authenticate(
                    saveUser("orgadmin.agg.b@test.invalid", UserRole.ORG_ADMIN, orgB));
            mockMvc.perform(get(aggregateUrl())).andExpect(status().isForbidden());
        }

        private void insertNarrative(UUID distancePillarId, String name, String status,
                                     String itemsJson) {
            jdbc.update("""
                    INSERT INTO shift_narratives (cohort_id, org_id, user_id, baseline_pillar_id,
                                                  distance_pillar_id, pillar_name_snapshot,
                                                  items, status, approved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                    """, cohortId, orgA.getId(), founder1.getId(), distancePillarId,
                    distancePillarId, name, itemsJson, status);
        }
    }

    /* ------------------------------------------------------ scoring config */

    @Nested
    class ScoringConfigEndpoints {

        @Test
        void getReturnsShippedDefaults_putValidatesAsFieldErrors() throws Exception {
            TestAuthentication.authenticateAsSuperAdmin(userRepository);
            // The service resolves updatedBy → email via raw SQL; flush so the
            // freshly-persisted super admin is visible to it inside this tx.
            userRepository.flush();

            mockMvc.perform(get("/api/admin/scoring-config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participationFormula.categories[0].key", is("assignments")))
                    .andExpect(jsonPath("$.participationFormula.updatedAt").doesNotExist())
                    .andExpect(jsonPath("$.shiftBands.bands[0].key", is("decline")))
                    .andExpect(jsonPath("$.qualityTags.tags.length()", is(3)));

            mockMvc.perform(put("/api/admin/scoring-config/participation-formula")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"categories":[
                                      {"key":"assignments","label":"Assignments","weight":50,"computed":true},
                                      {"key":"workshops","label":"Workshops","weight":40,"computed":false}]}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.categories").exists());

            mockMvc.perform(put("/api/admin/scoring-config/participation-bands")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bands":[{"key":"band_1","label":"High","min":10,"max":100}]}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.bands").exists());

            // A valid PUT lands and stamps updatedAt/updatedBy (email).
            mockMvc.perform(put("/api/admin/scoring-config/participation-bands")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bands":[{"key":"band_1","label":"All","min":0,"max":100}]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bands.length()", is(1)))
                    .andExpect(jsonPath("$.updatedAt").exists())
                    .andExpect(jsonPath("$.updatedBy", is("test-super-admin@bvisionry.invalid")));

            TestAuthentication.authenticate(founder1);
            mockMvc.perform(get("/api/admin/scoring-config"))
                    .andExpect(status().isForbidden());
        }
    }

    /* ------------------------------------------------------------- seeding */

    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.saveAndFlush(org);
    }

    private User saveUser(String email, UserRole role, Organization org) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
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
        jdbc.update("""
                INSERT INTO pipelines (id, name, status, created_by)
                VALUES (?, ?, 'PUBLISHED', ?)
                """, id, name, founder1.getId());
        return id;
    }

    private UUID insertPillar(UUID pipelineId, String name, int order) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, ?, ?)",
                id, pipelineId, name, order);
        return id;
    }

    private UUID insertEvaluatedSubmission(User user, UUID pipelineId, int daysAgo,
                                           BigDecimal overallScore) {
        // Assignments are unique per (org, pipeline, user) — a retake is a
        // second submission on the SAME assignment (maxCheckIns), like prod.
        UUID assignmentId = jdbc
                .queryForList("SELECT id FROM assignments WHERE user_id = ? AND pipeline_id = ?",
                        UUID.class, user.getId(), pipelineId)
                .stream().findFirst().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                            VALUES (?, ?, ?, ?, ?)
                            """, id, pipelineId, user.getOrganization().getId(), user.getId(),
                            user.getId());
                    return id;
                });
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now() - make_interval(days => ?), now() - make_interval(days => ?))
                """, submissionId, assignmentId, user.getId(), daysAgo, daysAgo);
        jdbc.update("""
                INSERT INTO overall_summaries (submission_id, overall_score_percentage)
                VALUES (?, ?)
                """, submissionId, overallScore);
        return submissionId;
    }

    private void insertPillarEval(UUID submissionId, UUID pillarId, String score, String label) {
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label)
                VALUES (?, ?, ?::numeric, ?)
                """, submissionId, pillarId, score, label);
    }
}
