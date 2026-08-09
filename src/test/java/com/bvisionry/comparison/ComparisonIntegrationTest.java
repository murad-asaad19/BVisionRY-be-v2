package com.bvisionry.comparison;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.event.EvaluationEvents;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.MappingSource;
import com.bvisionry.comparison.domain.PillarComparisonState;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
        UUID distanceFocus = insertPillar(distancePipelineId, "Focus & Flow", 1);
        distanceResilience = insertPillar(distancePipelineId, "Resilience", 2);

        cohortId = insertCohort(orgA.getId(), "Cohort 12", founder1.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortId, founder2.getId());
        jdbc.update("""
                INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
                VALUES (?, ?, ?)
                """, cohortId, baselinePipelineId, distancePipelineId);

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
         * Same-instrument pairs are allowed since the typed task spine (D1):
         * the DISTANCE milestone tag — not "latest evaluated" — resolves the
         * distance submission, so an equal pair can no longer self-compare.
         */
        @Test
        void equalBaselineAndDistancePipelines_areAccepted() throws Exception {
            TestAuthentication.authenticate(
                    saveUser("orgadmin.pair@test.invalid", UserRole.ORG_ADMIN, orgA));
            mockMvc.perform(put("/api/organizations/" + orgA.getId()
                            + "/cohorts/" + cohortId + "/program/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"stageLabel":"Week","dripEnabled":true,"dueSoonDays":3,
                                     "baselinePipelineId":"%s","distancePipelineId":"%s"}
                                    """.formatted(baselinePipelineId, baselinePipelineId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.baselinePipelineId", is(baselinePipelineId.toString())))
                    .andExpect(jsonPath("$.distancePipelineId", is(baselinePipelineId.toString())));
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
                    INSERT INTO program_modules (id, org_id, cohort_id, name)
                    VALUES (?, ?, ?, 'Distance module')
                    """, moduleId, orgA.getId(), samePairCohortId);
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
                    mappingService.mappingForPair(baselinePipelineId, distancePipelineId);

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
                    mappingService.mappingForPair(baselinePipelineId, distancePipelineId);
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
            PillarMappingResponse afterMap = mappingService.map(baselinePipelineId,
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
        jdbc.update("INSERT INTO cohorts (id, org_id, name) VALUES (?, ?, ?)", id, orgId, name);
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
                            """, id, pipelineId, orgA.getId(), user.getId(), user.getId());
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
