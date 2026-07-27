package com.bvisionry.insights;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ROI report's policy story, end to end against the real schema:
 *
 * <ul>
 *   <li>intake vs latest is FIRST vs LAST — a founder with three assessments is
 *       compared #1 to #3, never through the middle one;</li>
 *   <li>a founder with a single assessment shows an intake and NO delta, and
 *       does not enter the paired pillar averages;</li>
 *   <li>completion counts LIVE tasks of the cohort's modules against SUBMITTED
 *       program submissions — drafts on either side count for nothing;</li>
 *   <li>org A's report contains none of org B's founders, scores or names, on
 *       the JSON and inside the generated workbook;</li>
 *   <li>the PDF and XLSX routes return real, openable documents with the right
 *       content types;</li>
 *   <li>COACH / INSTRUCTOR / MEMBER and foreign org admins never clear the gate;
 *       a foreign cohort or an unpublished pipeline reads as absent (404);</li>
 *   <li>ENTITLEMENT (RULING 3): a FREE org's own admin is 403 premium_required
 *       on ALL THREE routes — JSON, PDF and XLSX, no split — and SUPER_ADMIN
 *       bypasses.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class RoiReportIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization orgA;
    private Organization orgB;
    private User orgAdminA;
    private User orgAdminB;
    private UUID pipelineId;
    private UUID pillarVision;   // display_order 1 -> pillars[0]
    private UUID pillarMarket;   // display_order 2 -> pillars[1]
    private UUID cohortA;
    private UUID cohortB;
    private int emailSeq;

    @BeforeEach
    void seed() {
        orgA = saveOrg("ROI Org A");
        orgB = saveOrg("ROI Org B");
        orgAdminA = saveUser("roi.admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        orgAdminB = saveUser("roi.admin.b@test.invalid", UserRole.ORG_ADMIN, orgB);

        pipelineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pipelines (id, name, status, created_by)
                VALUES (?, 'Founder Readiness', 'PUBLISHED', ?)
                """, pipelineId, orgAdminA.getId());
        pillarVision = insertPillar("Vision", 1);
        pillarMarket = insertPillar("Market", 2);

        cohortA = insertCohort(orgA.getId(), "Winter 2026");
        cohortB = insertCohort(orgB.getId(), "Other Org Cohort");
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* ------------------------------------------------ intake vs latest math */

    @Test
    void threeAssessmentsCompareFirstToLast_neverTheMiddleOne() throws Exception {
        // 90 days ago: 30.0 · 60 days ago: 90.0 (the middle) · today: 50.0.
        // A first-to-last comparison moves +20.0; anything touching the middle
        // would show +60 or -40 instead.
        UUID founder = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        UUID assignment = insertAssignment(orgA.getId(), founder);
        insertSubmission(assignment, founder, 30.0, 90);
        insertSubmission(assignment, founder, 90.0, 60);
        insertSubmission(assignment, founder, 50.0, 0);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName", is("ROI Org A")))
                .andExpect(jsonPath("$.programName", is("Winter 2026")))
                .andExpect(jsonPath("$.assessmentName", is("Founder Readiness")))
                .andExpect(jsonPath("$.cohortSize", is(1)))
                .andExpect(jsonPath("$.foundersMeasured", is(1)))
                .andExpect(jsonPath("$.foundersRemeasured", is(1)))
                // Pillar movement: 30.0 -> 50.0.
                .andExpect(jsonPath("$.pillars", hasSize(2)))
                .andExpect(jsonPath("$.pillars[0].pillarName", is("Vision")))
                .andExpect(jsonPath("$.pillars[0].foundersPaired", is(1)))
                .andExpect(jsonPath("$.pillars[0].intakeAverage", is(30.0)))
                .andExpect(jsonPath("$.pillars[0].latestAverage", is(50.0)))
                .andExpect(jsonPath("$.pillars[0].delta", is(20.0)))
                .andExpect(jsonPath("$.pillars[0].direction", is("UP")))
                // The founder row carries the same first-to-last move.
                .andExpect(jsonPath("$.founders", hasSize(1)))
                .andExpect(jsonPath("$.founders[0].founderName", is("Ada Lovelace")))
                .andExpect(jsonPath("$.founders[0].assessments", is(3)))
                .andExpect(jsonPath("$.founders[0].intakeScore", is(30.0)))
                .andExpect(jsonPath("$.founders[0].latestScore", is(50.0)))
                .andExpect(jsonPath("$.founders[0].delta", is(20.0)))
                .andExpect(jsonPath("$.founders[0].direction", is("UP")));
    }

    @Test
    void singleAssessmentFounderShowsIntakeOnly_andIsNotPaired() throws Exception {
        // One founder moved 40 -> 60; the other was measured once at 10 and must
        // not drag the pillar average — it averages the PAIRED population only.
        UUID moved = enrol(orgA.getId(), cohortA, "Beatrice Moved");
        UUID movedAssignment = insertAssignment(orgA.getId(), moved);
        insertSubmission(movedAssignment, moved, 40.0, 30);
        insertSubmission(movedAssignment, moved, 60.0, 0);

        UUID once = enrol(orgA.getId(), cohortA, "Cyrus Once");
        insertSubmission(insertAssignment(orgA.getId(), once), once, 10.0, 15);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortSize", is(2)))
                .andExpect(jsonPath("$.foundersMeasured", is(2)))
                .andExpect(jsonPath("$.foundersRemeasured", is(1)))
                // 40 -> 60 only: the 10.0 single point is nowhere in the average.
                .andExpect(jsonPath("$.pillars[0].foundersPaired", is(1)))
                .andExpect(jsonPath("$.pillars[0].intakeAverage", is(40.0)))
                .andExpect(jsonPath("$.pillars[0].latestAverage", is(60.0)))
                .andExpect(jsonPath("$.pillars[0].delta", is(20.0)))
                // Ordered by name: Beatrice, then Cyrus.
                .andExpect(jsonPath("$.founders[1].founderName", is("Cyrus Once")))
                .andExpect(jsonPath("$.founders[1].assessments", is(1)))
                .andExpect(jsonPath("$.founders[1].intakeScore", is(10.0)))
                .andExpect(jsonPath("$.founders[1].latestScore", nullValue()))
                .andExpect(jsonPath("$.founders[1].latestOn", nullValue()))
                .andExpect(jsonPath("$.founders[1].delta", nullValue()))
                .andExpect(jsonPath("$.founders[1].direction", nullValue()));
    }

    @Test
    void pillarWithNoSecondMeasurementCarriesNoNumbers() throws Exception {
        // Both assessments scored Vision; only the first scored Market, so
        // Market has no pair and must stay empty rather than showing a drop.
        UUID founder = enrol(orgA.getId(), cohortA, "Dana Partial");
        UUID assignment = insertAssignment(orgA.getId(), founder);
        // Both assessments carry the SAME canonical overall (55.0); only the
        // pillar breakdown differs, so a pillar with no pair cannot borrow
        // movement from the founder-level number.
        UUID first = insertSubmission(assignment, founder, 40.0, 55.0, 30);
        insertEval(first, pillarMarket, 80.0);
        insertSubmission(assignment, founder, 60.0, 55.0, 0);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[1].pillarName", is("Market")))
                .andExpect(jsonPath("$.pillars[1].foundersPaired", is(0)))
                .andExpect(jsonPath("$.pillars[1].intakeAverage", nullValue()))
                .andExpect(jsonPath("$.pillars[1].latestAverage", nullValue()))
                .andExpect(jsonPath("$.pillars[1].delta", nullValue()))
                .andExpect(jsonPath("$.pillars[1].direction", nullValue()))
                // Vision DID move 40 -> 60 while the founder's canonical overall
                // stayed flat: the two views are independent by design.
                .andExpect(jsonPath("$.pillars[0].delta", is(20.0)))
                .andExpect(jsonPath("$.founders[0].intakeScore", is(55.0)))
                .andExpect(jsonPath("$.founders[0].latestScore", is(55.0)))
                .andExpect(jsonPath("$.founders[0].delta", is(0.0)))
                .andExpect(jsonPath("$.founders[0].direction", is("FLAT")));
    }

    @Test
    void unassessedCohortReportsHonestEmptyState() throws Exception {
        enrol(orgA.getId(), cohortA, "Eve Unmeasured");

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortSize", is(1)))
                .andExpect(jsonPath("$.foundersMeasured", is(0)))
                .andExpect(jsonPath("$.foundersRemeasured", is(0)))
                .andExpect(jsonPath("$.periodStart", nullValue()))
                .andExpect(jsonPath("$.periodEnd", nullValue()))
                .andExpect(jsonPath("$.completionRate", nullValue()))
                .andExpect(jsonPath("$.pillars[0].delta", nullValue()))
                .andExpect(jsonPath("$.founders[0].assessments", is(0)))
                .andExpect(jsonPath("$.founders[0].intakeScore", nullValue()));
    }

    /* ------------------------------------------------------ completion rate */

    @Test
    void completionCountsLiveTasksAndSubmittedAnswersOnly() throws Exception {
        UUID module = insertModule(orgA.getId(), cohortA, "Module 1");
        UUID liveOne = insertTask(module, "Task 1", "LIVE");
        UUID liveTwo = insertTask(module, "Task 2", "LIVE");
        UUID draftTask = insertTask(module, "Draft task", "DRAFT");

        UUID first = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        UUID second = enrol(orgA.getId(), cohortA, "Bo Peep");
        insertProgramSubmission(liveOne, first, "SUBMITTED");
        insertProgramSubmission(liveTwo, first, "DRAFT");     // saved, not submitted
        insertProgramSubmission(draftTask, first, "SUBMITTED"); // task isn't live
        insertProgramSubmission(liveOne, second, "SUBMITTED");
        insertProgramSubmission(liveTwo, second, "SUBMITTED");

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                // 2 LIVE tasks x 2 founders = 4 assigned; 3 submitted = 75.0%.
                .andExpect(jsonPath("$.tasksAssigned", is(4)))
                .andExpect(jsonPath("$.tasksCompleted", is(3)))
                .andExpect(jsonPath("$.completionRate", is(75.0)))
                .andExpect(jsonPath("$.founders[0].founderName", is("Ada Lovelace")))
                .andExpect(jsonPath("$.founders[0].tasksAssigned", is(2)))
                .andExpect(jsonPath("$.founders[0].tasksCompleted", is(1)))
                .andExpect(jsonPath("$.founders[0].completionRate", is(50.0)))
                .andExpect(jsonPath("$.founders[1].completionRate", is(100.0)));
    }

    @Test
    void memberAssignedModulesReachOnlyTheirAudience() throws Exception {
        UUID module = insertModule(orgA.getId(), cohortA, "Targeted module");
        jdbc.update("UPDATE program_modules SET assign_mode = 'MEMBERS' WHERE id = ?", module);
        insertTask(module, "Task 1", "LIVE");

        UUID targeted = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        enrol(orgA.getId(), cohortA, "Bo Peep");
        jdbc.update("INSERT INTO program_module_members (module_id, user_id) VALUES (?, ?)",
                module, targeted);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasksAssigned", is(1)))
                .andExpect(jsonPath("$.founders[0].tasksAssigned", is(1)))
                .andExpect(jsonPath("$.founders[1].tasksAssigned", is(0)))
                .andExpect(jsonPath("$.founders[1].completionRate", nullValue()));
    }

    /* ------------------------------------------------------- tenant scoping */

    @Test
    void reportContainsNoneOfTheOtherTenantsFoundersOrScores() throws Exception {
        UUID ours = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        UUID ourAssignment = insertAssignment(orgA.getId(), ours);
        insertSubmission(ourAssignment, ours, 40.0, 30);
        insertSubmission(ourAssignment, ours, 60.0, 0);

        // THE ORG PREDICATE'S OWN PIN: our founder also holds an assignment on
        // org B's books for the same pipeline, scored far outside 40..60. It
        // must not reach the report — drop `a.organization_id = :orgId` from the
        // EVALUATED CTE and this founder becomes a 3-assessment 40 -> 999 row,
        // failing every assertion below.
        insertSubmission(insertAssignment(orgB.getId(), ours), ours, 999.0, 1);

        // …and the cohort arm: our founder is ALSO enrolled in org B's cohort,
        // so `c.org_id = :orgId` is what keeps that membership from widening
        // org A's roster.
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortB, ours);

        UUID theirs = enrol(orgB.getId(), cohortB, "Zebedee Foreign");
        UUID theirAssignment = insertAssignment(orgB.getId(), theirs);
        insertSubmission(theirAssignment, theirs, 5.0, 30);
        insertSubmission(theirAssignment, theirs, 95.0, 0);

        TestAuthentication.authenticate(orgAdminA);
        String body = mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortSize", is(1)))
                .andExpect(jsonPath("$.pillars[0].foundersPaired", is(1)))
                .andExpect(jsonPath("$.pillars[0].intakeAverage", is(40.0)))
                .andExpect(jsonPath("$.pillars[0].latestAverage", is(60.0)))
                // The stray org-B assignment is invisible: still 2 assessments,
                // still a 40 -> 60 move, no trace of the 999.
                .andExpect(jsonPath("$.founders[0].assessments", is(2)))
                .andExpect(jsonPath("$.founders[0].latestScore", is(60.0)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("Ada Lovelace");
        assertThat(body).doesNotContain("Zebedee Foreign");
        assertThat(body).doesNotContain("ROI Org B");
        assertThat(body).doesNotContain("Other Org Cohort");
        // Funder-facing: names, never identifiers.
        assertThat(body).doesNotContain(ours.toString());
        assertThat(body).doesNotContain(theirs.toString());
        assertThat(body).doesNotContain("roi.founder");

        // …and the same exclusion survives the export path, asserted on the
        // workbook's real cell values rather than a byte search.
        byte[] xlsx = mockMvc.perform(export(orgA, cohortA, "xlsx"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String cells = String.join("\n", cellText(xlsx));
        assertThat(cells).contains("Ada Lovelace");
        assertThat(cells).doesNotContain("Zebedee Foreign");
        assertThat(cells).doesNotContain("ROI Org B");
    }

    /* -------------------------------------------- canonical score + axis */

    @Test
    void founderScoreIsTheCanonicalOverallSummary_notThePillarMean() throws Exception {
        // The platform's overall score is a WEIGHTED number written to
        // overall_summaries; MemberResultsService and the founder's own results
        // PDF read it. Seed one that deliberately differs from the pillar mean
        // so the report can only pass by reading the canonical column.
        UUID founder = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        UUID assignment = insertAssignment(orgA.getId(), founder);
        insertSubmission(assignment, founder, 40.0, 51.0, 30);   // pillar 40, overall 51
        insertSubmission(assignment, founder, 60.0, 81.0, 0);    // pillar 60, overall 81

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.founders[0].intakeScore", is(51.0)))
                .andExpect(jsonPath("$.founders[0].latestScore", is(81.0)))
                .andExpect(jsonPath("$.founders[0].delta", is(30.0)))
                // The PILLAR table stays the paired per-pillar view — it is not
                // the overall score and must not follow it.
                .andExpect(jsonPath("$.pillars[0].intakeAverage", is(40.0)))
                .andExpect(jsonPath("$.pillars[0].latestAverage", is(60.0)));
    }

    @Test
    void quarantinedNeedsReviewRunIsNotAMeasurement() throws Exception {
        // THE FABRICATED-RISE SHAPE. A failed AI run persists ai_failed pillar
        // rows at 0.00 AND a 0.00 overall summary under NEEDS_REVIEW. Counting
        // it as an intake would print "0.0 -> 71.0, +71.0 UP" in a funder PDF
        // whose own footnote promises nothing is estimated. The founder has ONE
        // real assessment, so the honest answer is no movement at all.
        UUID founder = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        UUID assignment = insertAssignment(orgA.getId(), founder);
        insertQuarantinedSubmission(assignment, founder, 30);
        insertSubmission(assignment, founder, 71.0, 0);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foundersMeasured", is(1)))
                .andExpect(jsonPath("$.foundersRemeasured", is(0)))
                // The founder table: one assessment, the clean one, no delta.
                .andExpect(jsonPath("$.founders[0].assessments", is(1)))
                .andExpect(jsonPath("$.founders[0].intakeScore", is(71.0)))
                .andExpect(jsonPath("$.founders[0].latestScore", nullValue()))
                .andExpect(jsonPath("$.founders[0].delta", nullValue()))
                .andExpect(jsonPath("$.founders[0].direction", nullValue()))
                // …and the pillar movement is untouched by the quarantined 0.00.
                .andExpect(jsonPath("$.pillars[0].foundersPaired", is(0)))
                .andExpect(jsonPath("$.pillars[0].intakeAverage", nullValue()))
                .andExpect(jsonPath("$.pillars[0].latestAverage", nullValue()))
                .andExpect(jsonPath("$.pillars[0].delta", nullValue()));
    }

    @Test
    void foreignOrOrphanedCohortMemberNeverReachesTheRoster() throws Exception {
        // Nothing clears cohort_members when a member is moved between orgs
        // (MoveMemberService) or removed and anonymised (MemberService), so a
        // membership row outlives the membership. Without
        // u.organization_id = :orgId these two appear in a funder-facing roster
        // under their LIVE name and inflate cohortSize + the completion
        // denominator as permanent zero-assessment ghosts.
        UUID ours = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        insertSubmission(insertAssignment(orgA.getId(), ours), ours, 40.0, 0);

        UUID moved = enrol(orgB.getId(), cohortB, "Zebedee Moved");
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohortA, moved);

        UUID orphaned = enrol(orgA.getId(), cohortA, "Olive Orphaned");
        jdbc.update("UPDATE users SET organization_id = NULL WHERE id = ?", orphaned);

        TestAuthentication.authenticate(orgAdminA);
        String body = mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortSize", is(1)))
                .andExpect(jsonPath("$.founders", hasSize(1)))
                .andExpect(jsonPath("$.founders[0].founderName", is("Ada Lovelace")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Zebedee Moved");
        assertThat(body).doesNotContain("Olive Orphaned");

        // The same exclusion survives the export path.
        String cells = String.join("\n", cellText(mockMvc.perform(export(orgA, cohortA, "xlsx"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()));
        assertThat(cells).contains("Ada Lovelace");
        assertThat(cells).doesNotContain("Zebedee Moved");
        assertThat(cells).doesNotContain("Olive Orphaned");
    }

    @Test
    void personalPillarIsNotAnAxisRow() throws Exception {
        // V30 auto-creates a PERSONAL "General Information" pillar on every
        // pipeline and EvaluationEngine strips it before scoring, so it can
        // never be evaluated. It must not appear as a permanent
        // "not yet measured twice" row in a funder document.
        insertPersonalPillar();

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars", hasSize(2)))
                .andExpect(jsonPath("$.pillars[0].pillarName", is("Vision")))
                .andExpect(jsonPath("$.pillars[1].pillarName", is("Market")));
    }

    @Test
    void foreignCohortAndUnpublishedPipelineReadAsAbsent() throws Exception {
        UUID draftPipeline = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pipelines (id, name, status, created_by)
                VALUES (?, 'Unreleased Draft', 'DRAFT', ?)
                """, draftPipeline, orgAdminA.getId());

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(report(orgA, cohortB)).andExpect(status().isNotFound());
        mockMvc.perform(report(orgA, UUID.randomUUID())).andExpect(status().isNotFound());
        mockMvc.perform(get(roiPath(orgA, "roi-report")
                        + "?cohortId=" + cohortA + "&pipelineId=" + draftPipeline))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(roiPath(orgA, "roi-report")
                        + "?cohortId=" + cohortA + "&pipelineId=" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void coachInstructorMemberAndForeignAdminAreDenied() throws Exception {
        for (UserRole role : List.of(UserRole.COACH, UserRole.INSTRUCTOR, UserRole.MEMBER)) {
            TestAuthentication.authenticate(
                    saveUser("roi." + role.name().toLowerCase() + "@test.invalid", role, orgA));
            mockMvc.perform(report(orgA, cohortA)).andExpect(status().isForbidden());
            mockMvc.perform(export(orgA, cohortA, "pdf")).andExpect(status().isForbidden());
            mockMvc.perform(export(orgA, cohortA, "xlsx")).andExpect(status().isForbidden());
        }

        // A foreign org admin never reaches another tenant's report, on any of
        // the three routes — the exports are as guarded as the JSON.
        TestAuthentication.authenticate(orgAdminB);
        mockMvc.perform(report(orgA, cohortA)).andExpect(status().isForbidden());
        mockMvc.perform(export(orgA, cohortA, "pdf")).andExpect(status().isForbidden());
        mockMvc.perform(export(orgA, cohortA, "xlsx")).andExpect(status().isForbidden());

        // …and SUPER_ADMIN may read any org's, likewise on all three.
        TestAuthentication.authenticate(saveUser("roi.super@test.invalid", UserRole.SUPER_ADMIN, null));
        mockMvc.perform(report(orgA, cohortA)).andExpect(status().isOk());
        mockMvc.perform(export(orgA, cohortA, "pdf")).andExpect(status().isOk());
        mockMvc.perform(export(orgA, cohortA, "xlsx")).andExpect(status().isOk());
    }

    /* --------------------------------------------------------- entitlement */

    @Test
    void freeOrgAdminIsGatedOnAllThreeRoutes_andSuperAdminBypasses() throws Exception {
        // RULING 3: exports and the on-screen report get IDENTICAL treatment —
        // no split. Gating only the downloads would monetize nothing, since a
        // free on-screen report is one browser print-to-PDF from a funder doc.
        Organization freeOrg = saveFreeOrg("ROI Free Org");
        User freeAdmin = saveUser("roi.admin.free@test.invalid", UserRole.ORG_ADMIN, freeOrg);
        UUID freeCohort = insertCohort(freeOrg.getId(), "Free Cohort");
        UUID founder = enrol(freeOrg.getId(), freeCohort, "Grace Hopper");
        UUID assignment = insertAssignment(freeOrg.getId(), founder);
        insertSubmission(assignment, founder, 30.0, 90);
        insertSubmission(assignment, founder, 50.0, 0);

        TestAuthentication.authenticate(freeAdmin);
        // The JSON…
        mockMvc.perform(report(freeOrg, freeCohort))
                .andExpect(status().isForbidden())
                // The web lock state keys off this body, not the bare status.
                .andExpect(jsonPath("$.error", is("premium_required")))
                .andExpect(jsonPath("$.feature", is("roi_report")));
        // …and BOTH exports, identically.
        for (String ext : List.of("pdf", "xlsx")) {
            mockMvc.perform(export(freeOrg, freeCohort, ext))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error", is("premium_required")))
                    .andExpect(jsonPath("$.feature", is("roi_report")));
        }

        // Super admin bypasses the guard on all three — demo and sales flows
        // are unaffected by the gate.
        TestAuthentication.authenticate(saveUser("roi.super.free@test.invalid", UserRole.SUPER_ADMIN, null));
        mockMvc.perform(report(freeOrg, freeCohort)).andExpect(status().isOk());
        mockMvc.perform(export(freeOrg, freeCohort, "pdf")).andExpect(status().isOk());
        mockMvc.perform(export(freeOrg, freeCohort, "xlsx")).andExpect(status().isOk());
    }

    /* --------------------------------------------------------------- exports */

    @Test
    void pdfAndXlsxDownloadsAreRealDocuments() throws Exception {
        UUID founder = enrol(orgA.getId(), cohortA, "Ada Lovelace");
        UUID assignment = insertAssignment(orgA.getId(), founder);
        insertSubmission(assignment, founder, 40.0, 30);
        insertSubmission(assignment, founder, 60.0, 0);

        TestAuthentication.authenticate(orgAdminA);
        byte[] pdf = mockMvc.perform(export(orgA, cohortA, "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"Program_Outcomes_Winter_2026.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).hasSizeGreaterThan(1_000);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

        // A preview renders inline instead of downloading — same document.
        mockMvc.perform(get(roiPath(orgA, "roi-report.pdf") + query(cohortA) + "&mode=preview"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"Program_Outcomes_Winter_2026.pdf\""));

        byte[] xlsx = mockMvc.perform(export(orgA, cohortA, "xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"Program_Outcomes_Winter_2026.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("Overview");
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("Pillar movement");
            assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("Founder deltas");
            // Vision moved 40 -> 60: the workbook carries the numbers as numbers.
            Sheet movement = wb.getSheetAt(1);
            assertThat(movement.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Vision");
            assertThat(movement.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(40.0);
            assertThat(movement.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(60.0);
            assertThat(movement.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(20.0);
            assertThat(movement.getRow(1).getCell(5).getStringCellValue()).isEqualTo("Up");
            // Intake/latest are real DATE cells, not the text "2026-05-17", so a
            // funder can sort and filter the founder table by date. The SQL
            // resolves the timestamp AT TIME ZONE 'UTC', so the expectation is
            // UTC too — not the JVM's zone, which would flake either side of
            // midnight on any machine that is not on UTC.
            Sheet deltas = wb.getSheetAt(2);
            assertThat(deltas.getRow(1).getCell(2).getCellType())
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(deltas.getRow(1).getCell(2).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(30));
        }
    }

    /* ------------------------------------------------------------- helpers */

    private String roiPath(Organization org, String resource) {
        return "/api/organizations/" + org.getId() + "/" + resource;
    }

    private String query(UUID cohortId) {
        return "?cohortId=" + cohortId + "&pipelineId=" + pipelineId;
    }

    private MockHttpServletRequestBuilder report(Organization org, UUID cohortId) {
        return get(roiPath(org, "roi-report") + query(cohortId));
    }

    private MockHttpServletRequestBuilder export(Organization org, UUID cohortId, String ext) {
        return get(roiPath(org, "roi-report." + ext) + query(cohortId));
    }

    /** Every string cell in the workbook — the readable content of the export. */
    private static List<String> cellText(byte[] xlsx) throws Exception {
        List<String> out = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            wb.forEach(sheet -> sheet.forEach(row -> row.forEach(cell -> {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                    out.add(cell.getStringCellValue());
                }
            })));
        }
        return out;
    }

    /**
     * ROI reporting is entitlement-gated (RULING 3), so every fixture org that
     * is expected to READ the surface is PREMIUM. The FREE case is its own test.
     */
    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        org.setSubscriptionTier(SubscriptionTier.GROWTH);
        return organizationRepository.saveAndFlush(org);
    }

    /** A root org on the FREE tier — the entitlement gate's subject. */
    private Organization saveFreeOrg(String name) {
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

    private UUID insertPillar(String name, int displayOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, ?, ?)",
                id, pipelineId, name, displayOrder);
        return id;
    }

    /** The auto-created, never-evaluated PERSONAL pillar (V30), at order 0. */
    private void insertPersonalPillar() {
        jdbc.update("""
                INSERT INTO pillars (pipeline_id, name, display_order, type)
                VALUES (?, 'General Information', 0, 'PERSONAL')
                """, pipelineId);
    }

    /** The platform's canonical overall score for a submission. */
    private void insertOverallSummary(UUID submissionId, double overallScore) {
        jdbc.update("""
                INSERT INTO overall_summaries (submission_id, overall_score_percentage)
                VALUES (?, ?)
                """, submissionId, overallScore);
    }

    private UUID insertCohort(UUID orgId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, org_id, name) VALUES (?, ?, ?)", id, orgId, name);
        return id;
    }

    /** A founder of {@code orgId}, enrolled in {@code cohortId}. */
    private UUID enrol(UUID orgId, UUID cohortId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, ?, ?, 'MEMBER', 'ACTIVE', ?)
                """, id, "roi.founder." + (++emailSeq) + "@test.invalid", name, orgId);
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohortId, id);
        return id;
    }

    private UUID insertAssignment(UUID orgId, UUID userId) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, orgId, userId, orgAdminA.getId());
        return assignmentId;
    }

    /**
     * A clean EVALUATED submission {@code daysAgo} in the past: Vision scored,
     * and the canonical overall summary the platform always writes alongside it.
     */
    private UUID insertSubmission(UUID assignmentId, UUID userId, double visionScore, int daysAgo) {
        return insertSubmission(assignmentId, userId, visionScore, visionScore, daysAgo);
    }

    /** As above, with the canonical overall deliberately unequal to the pillar. */
    private UUID insertSubmission(UUID assignmentId, UUID userId, double visionScore,
                                  double overallScore, int daysAgo) {
        UUID submissionId = insertSubmissionRow(assignmentId, userId, "EVALUATED", daysAgo);
        insertEval(submissionId, pillarVision, visionScore, false);
        insertOverallSummary(submissionId, overallScore);
        return submissionId;
    }

    /**
     * A QUARANTINED run: the AI could not produce valid output even after the
     * engine's repair retries, so partial rows are persisted with
     * {@code ai_failed} and zeroed scores — including a 0.00 overall summary,
     * written before the degraded check — and the submission is NEEDS_REVIEW.
     * Exactly the shape EvaluationService leaves behind ("fail loud, not quiet").
     */
    private UUID insertQuarantinedSubmission(UUID assignmentId, UUID userId, int daysAgo) {
        UUID submissionId = insertSubmissionRow(assignmentId, userId, "NEEDS_REVIEW", daysAgo);
        insertEval(submissionId, pillarVision, 0.0, true);
        insertOverallSummary(submissionId, 0.0);
        return submissionId;
    }

    private UUID insertSubmissionRow(UUID assignmentId, UUID userId, String status, int daysAgo) {
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, ?, ?, now() - make_interval(days => ?))
                """, submissionId, assignmentId, userId, status, daysAgo);
        return submissionId;
    }

    private void insertEval(UUID submissionId, UUID pillarId, double score) {
        insertEval(submissionId, pillarId, score, false);
    }

    private void insertEval(UUID submissionId, UUID pillarId, double score, boolean aiFailed) {
        jdbc.update("""
                INSERT INTO pillar_evaluations
                    (submission_id, pillar_id, score_percentage, maturity_label, ai_failed)
                VALUES (?, ?, ?, 'Strong', ?)
                """, submissionId, pillarId, score, aiFailed);
    }

    private UUID insertModule(UUID orgId, UUID cohortId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, org_id, cohort_id, name, assign_mode)
                VALUES (?, ?, ?, ?, 'ALL')
                """, id, orgId, cohortId, name);
        return id;
    }

    private UUID insertTask(UUID moduleId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO program_tasks (id, module_id, name, status) VALUES (?, ?, ?, ?)",
                id, moduleId, name, status);
        return id;
    }

    private void insertProgramSubmission(UUID taskId, UUID userId, String status) {
        jdbc.update("INSERT INTO program_submissions (task_id, user_id, status) VALUES (?, ?, ?)",
                taskId, userId, status);
    }
}
