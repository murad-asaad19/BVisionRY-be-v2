package com.bvisionry.insights;

import com.bvisionry.auth.OrgAccessGuard;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.security.OrgHierarchyPort;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The benchmarking surface's policy story, end to end against the real schema:
 *
 * <ul>
 *   <li>the min-sample floor (30) suppresses BOTH the org and the platform
 *       segment at 29 and admits them at 30 — boundary-pinned;</li>
 *   <li>the platform aggregate also requires 30 samples outside the path
 *       org's one-level FAMILY (complementary suppression — root + all the
 *       root's children, since the guard admits parent admins on sub-org
 *       paths) so no admitted caller can difference everything they know out
 *       of it and read a sub-floor residual — boundary-pinned per caller and
 *       on both family paths;</li>
 *   <li>the payload is anonymous: no org id, no org name, no per-org anything
 *       — asserted on the serialized response;</li>
 *   <li>org A's org segment excludes org B's data (two-tenant fixture);</li>
 *   <li>one founder is one sample — a re-assessment counts once, latest only;</li>
 *   <li>COACH / INSTRUCTOR / MEMBER and foreign org admins never clear the
 *       gate; a foreign cohort id reads as absent (404).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class BenchmarkIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrgHierarchyPort orgHierarchyPort;

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
        // Re-pin OrgAccessGuard's static hierarchy port to THIS context's
        // adapter — in a full run, later-built contexts (H2-backed slices)
        // leave the static pointing at their own storage, which would 403 the
        // parent-admin-on-sub-org-path case (same fix as
        // SubOrganizationIntegrationTest).
        new OrgAccessGuard(orgHierarchyPort);

        orgA = saveOrg("Benchmark Org A");
        orgB = saveOrg("Benchmark Org B");
        orgAdminA = saveUser("bm.admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        orgAdminB = saveUser("bm.admin.b@test.invalid", UserRole.ORG_ADMIN, orgB);

        pipelineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pipelines (id, name, status, created_by)
                VALUES (?, 'Benchmark Pipeline', 'PUBLISHED', ?)
                """, pipelineId, orgAdminA.getId());
        pillarVision = insertPillar("Vision", 1);
        pillarMarket = insertPillar("Market", 2);

        cohortA = insertCohort(orgA.getId(), "Cohort A");
        cohortB = insertCohort(orgB.getId(), "Cohort B");
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* -------------------------------------------------- min-sample boundary */

    @Test
    void at29OrgIsInsufficient_at30OrgRenders_whilePlatformStillNeedsForeignData() throws Exception {
        seedFounders(orgA.getId(), 29, 40.0, null);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minSample", is(30)))
                .andExpect(jsonPath("$.pillars", hasSize(2)))
                .andExpect(jsonPath("$.pillars[0].pillarName", is("Vision")))
                // Own org at n=29: the count is named (it drives the unlock
                // copy), the numbers are not.
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].org.sampleSize", is(29)))
                .andExpect(jsonPath("$.pillars[0].org.mean", nullValue()))
                // Platform: no number AND no observed count — the HAVING
                // floors keep the row from ever existing.
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", nullValue()))
                .andExpect(jsonPath("$.pillars[0].platform.mean", nullValue()))
                // No cohort requested -> no cohort segment.
                .andExpect(jsonPath("$.pillars[0].cohort", nullValue()));

        seedFounders(orgA.getId(), 1, 40.0, null);   // the 30th founder

        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].org.sampleSize", is(30)))
                .andExpect(jsonPath("$.pillars[0].org.mean", is(40.0)))
                // 30 total but ZERO foreign founders: the platform aggregate
                // would be exactly the caller's own org — complementary
                // suppression keeps it insufficient with no observed count.
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", nullValue()));
    }

    @Test
    void platformNeedsThirtyForeignSamples_notJustThirtyTotal() throws Exception {
        // (b) THE ATTACK SHAPE — own 29 / platform 30. The org segment being
        // suppressed does NOT protect the residual: the admin knows their own
        // founders' exact scores from own-tenant surfaces, so a platform row
        // here would hand them the single foreign founder's score via
        // 30*mean - sum(own). Complementary suppression: no row at all.
        seedFounders(orgA.getId(), 29, 40.0, null);
        seedFounders(orgB.getId(), 1, 90.0, null);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", nullValue()))
                .andExpect(jsonPath("$.pillars[0].platform.mean", nullValue()));

        // (a) own 30 / platform 31: the org segment unlocks, the platform
        // still refuses — its complement is a single foreign founder.
        seedFounders(orgA.getId(), 1, 40.0, null);
        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", nullValue()));

        // The gate is PER CALLER: the same aggregate from org B's side has a
        // 30-founder complement (all of org A), so it renders for org B —
        // (30*40 + 90) / 31 = 41.6 — while staying suppressed for org A.
        TestAuthentication.authenticate(orgAdminB);
        mockMvc.perform(benchmarks(orgB, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].org.sampleSize", is(1)))
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", is(31)))
                .andExpect(jsonPath("$.pillars[0].platform.mean", is(41.6)));

        // (c) own 30 / foreign 30: both floors clear for org A too.
        seedFounders(orgB.getId(), 29, 90.0, null);
        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", is(60)))
                .andExpect(jsonPath("$.pillars[0].platform.mean", is(65.0)));
    }

    @Test
    void complementExcludesTheWholeOrgFamily_notJustThePathOrg() throws Exception {
        // The default topology (V136): a root org whose founders live in a
        // "General" sub-org. The root's admin knows every family score, and
        // OrgAccessGuard admits them on BOTH paths — so a complement keyed on
        // the path org alone would let them address the sub-org's path,
        // count the family into the complement, and difference the single
        // external founder's score out of the aggregate.
        Organization root = saveOrg("Benchmark Root P");
        Organization sub = saveSubOrg("General", root);
        User rootAdmin = saveUser("bm.admin.p@test.invalid", UserRole.ORG_ADMIN, root);
        seedFounders(sub.getId(), 30, 40.0, null);
        seedFounders(orgB.getId(), 1, 90.0, null);   // 31 total, 1 outside the family

        TestAuthentication.authenticate(rootAdmin);
        // Path = the sub-org (guard admits the parent admin): family
        // complement is the single external founder -> suppressed, no count.
        mockMvc.perform(get("/api/organizations/" + sub.getId()
                        + "/benchmarks?pipelineId=" + pipelineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", nullValue()))
                .andExpect(jsonPath("$.pillars[0].platform.mean", nullValue()));
        // Path = the root itself: same family, same suppression.
        mockMvc.perform(get("/api/organizations/" + root.getId()
                        + "/benchmarks?pipelineId=" + pipelineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", nullValue()));

        // Once 30 founders exist OUTSIDE the family, both paths unlock:
        // (30*40 + 30*90) / 60 = 65.0.
        seedFounders(orgB.getId(), 29, 90.0, null);
        mockMvc.perform(get("/api/organizations/" + sub.getId()
                        + "/benchmarks?pipelineId=" + pipelineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", is(60)))
                .andExpect(jsonPath("$.pillars[0].platform.mean", is(65.0)));
        mockMvc.perform(get("/api/organizations/" + root.getId()
                        + "/benchmarks?pipelineId=" + pipelineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", is(60)));
    }

    /* --------------------------------------- cross-tenant scope + anonymity */

    @Test
    void orgSegmentExcludesTheOtherTenant_andPayloadNamesNoOrg() throws Exception {
        seedFounders(orgA.getId(), 30, 40.0, null);
        seedFounders(orgB.getId(), 30, 90.0, null);

        TestAuthentication.authenticate(orgAdminA);
        String body = mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                // Org A's own segment carries none of org B's 90s…
                .andExpect(jsonPath("$.pillars[0].org.sampleSize", is(30)))
                .andExpect(jsonPath("$.pillars[0].org.mean", is(40.0)))
                .andExpect(jsonPath("$.pillars[0].org.p25", is(40.0)))
                .andExpect(jsonPath("$.pillars[0].org.p75", is(40.0)))
                // …while the platform aggregate blends both tenants.
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", is(60)))
                .andExpect(jsonPath("$.pillars[0].platform.mean", is(65.0)))
                .andExpect(jsonPath("$.pillars[0].platform.p25", is(40.0)))
                .andExpect(jsonPath("$.pillars[0].platform.p75", is(90.0)))
                .andReturn().getResponse().getContentAsString();

        // AGGREGATE_ONLY: the serialized payload names no organization at all —
        // not the caller's, and certainly not the other tenant's.
        assertThat(body).doesNotContain(orgA.getId().toString());
        assertThat(body).doesNotContain(orgB.getId().toString());
        assertThat(body).doesNotContain("Benchmark Org A");
        assertThat(body).doesNotContain("Benchmark Org B");
        assertThat(body).doesNotContainIgnoringCase("organization");
        assertThat(body).doesNotContainIgnoringCase("orgId");
        assertThat(body).doesNotContainIgnoringCase("orgName");
    }

    @Test
    void oneFounderIsOneSample_latestAssessmentOnly() throws Exception {
        seedFounders(orgA.getId(), 29, 40.0, null);
        // The 30th founder was re-assessed: an old 10.0 and a current 40.0 on
        // the same assignment (one assignment per founder per pipeline).
        UUID reassessed = insertUser(orgA.getId());
        UUID assignmentId = insertAssignment(orgA.getId(), reassessed);
        insertSubmission(assignmentId, reassessed, 10.0, 10);
        insertSubmission(assignmentId, reassessed, 40.0, 0);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                // 30 founders, not 31 samples — and the superseded 10.0 moves
                // neither the mean nor the floor.
                .andExpect(jsonPath("$.pillars[0].org.sampleSize", is(30)))
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].org.mean", is(40.0)));
    }

    @Test
    void personalPillarIsNotAnAxisRow() throws Exception {
        // V30 auto-creates a PERSONAL "General Information" pillar on every
        // pipeline and EvaluationEngine strips it before scoring, so it can
        // never carry a pillar evaluation — as an axis row it would render a
        // permanent, meaningless "insufficient data" line on the panel.
        jdbc.update("""
                INSERT INTO pillars (pipeline_id, name, display_order, type)
                VALUES (?, 'General Information', 0, 'PERSONAL')
                """, pipelineId);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars", hasSize(2)))
                .andExpect(jsonPath("$.pillars[0].pillarName", is("Vision")))
                .andExpect(jsonPath("$.pillars[1].pillarName", is("Market")));
    }

    @Test
    void pillarsAreIndependentlySuppressed() throws Exception {
        List<UUID> submissions = seedFounders(orgA.getId(), 30, 40.0, null);
        // Only five founders were also evaluated on Market.
        for (int i = 0; i < 5; i++) {
            insertEval(submissions.get(i), pillarMarket, 70.0);
        }

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].pillarName", is("Vision")))
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[1].pillarName", is("Market")))
                .andExpect(jsonPath("$.pillars[1].org.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[1].org.sampleSize", is(5)))
                .andExpect(jsonPath("$.pillars[1].org.mean", nullValue()))
                .andExpect(jsonPath("$.pillars[1].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[1].platform.sampleSize", nullValue()));
    }

    /* ------------------------------------------------------ cohort segment */

    @Test
    void cohortBoundary_29Insufficient_30RendersItsAverage() throws Exception {
        List<UUID> inCohort = seedFounders(orgA.getId(), 29, 60.0, cohortA);
        UUID outside = insertUser(orgA.getId());
        insertEvaluatedSubmission(orgA.getId(), outside, 20.0, 0);

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, "&cohortId=" + cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortName", is("Cohort A")))
                .andExpect(jsonPath("$.pillars[0].cohort.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].cohort.sampleSize", is(29)))
                .andExpect(jsonPath("$.pillars[0].cohort.mean", nullValue()));

        // Enrol the 30th founder into the cohort — the segment unlocks and its
        // average is the cohort's, not the org's.
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortA, insertFounderWithScore(orgA.getId(), 60.0));

        mockMvc.perform(benchmarks(orgA, "&cohortId=" + cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars[0].cohort.sufficient", is(true)))
                .andExpect(jsonPath("$.pillars[0].cohort.sampleSize", is(30)))
                .andExpect(jsonPath("$.pillars[0].cohort.mean", is(60.0)))
                // The org segment (31 founders incl. the 20.0 outsider) differs.
                .andExpect(jsonPath("$.pillars[0].org.sampleSize", is(31)))
                .andExpect(jsonPath("$.pillars[0].org.mean", is(58.7)));

        assertThat(inCohort).hasSize(29);
    }

    @Test
    void foreignOrUnknownCohortReadsAsAbsent() throws Exception {
        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, "&cohortId=" + cohortB))
                .andExpect(status().isNotFound());
        mockMvc.perform(benchmarks(orgA, "&cohortId=" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    /* --------------------------------------------------------- empty paths */

    @Test
    void pipelineWithNoEvaluationsShowsInsufficientEverywhere() throws Exception {
        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(benchmarks(orgA, "&cohortId=" + cohortA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars", hasSize(2)))
                .andExpect(jsonPath("$.pillars[0].org.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].org.sampleSize", is(0)))
                .andExpect(jsonPath("$.pillars[0].cohort.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].cohort.sampleSize", is(0)))
                .andExpect(jsonPath("$.pillars[0].platform.sufficient", is(false)))
                .andExpect(jsonPath("$.pillars[0].platform.sampleSize", nullValue()));
    }

    @Test
    void unknownOrDraftPipelineReadsAsAbsent() throws Exception {
        // The axis query must not be an existence oracle for DRAFT pipelines'
        // pillar names: unpublished reads exactly like nonexistent.
        UUID draftPipeline = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pipelines (id, name, status, created_by)
                VALUES (?, 'Unreleased Draft', 'DRAFT', ?)
                """, draftPipeline, orgAdminA.getId());

        TestAuthentication.authenticate(orgAdminA);
        mockMvc.perform(get("/api/organizations/" + orgA.getId()
                        + "/benchmarks?pipelineId=" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/" + orgA.getId()
                        + "/benchmarks?pipelineId=" + draftPipeline))
                .andExpect(status().isNotFound());
    }

    /* -------------------------------------------------------- role denials */

    @Test
    void coachInstructorMemberAndForeignAdminAreDenied() throws Exception {
        // coach_never_sees: platform_analytics — COACH is outside this gate.
        TestAuthentication.authenticate(saveUser("bm.coach@test.invalid", UserRole.COACH, orgA));
        mockMvc.perform(benchmarks(orgA, "")).andExpect(status().isForbidden());

        TestAuthentication.authenticate(saveUser("bm.instructor@test.invalid", UserRole.INSTRUCTOR, orgA));
        mockMvc.perform(benchmarks(orgA, "")).andExpect(status().isForbidden());

        TestAuthentication.authenticate(saveUser("bm.member@test.invalid", UserRole.MEMBER, orgA));
        mockMvc.perform(benchmarks(orgA, "")).andExpect(status().isForbidden());

        // A foreign org admin never reaches another tenant's numbers…
        TestAuthentication.authenticate(orgAdminB);
        mockMvc.perform(benchmarks(orgA, "")).andExpect(status().isForbidden());

        // …and SUPER_ADMIN may read any org's.
        TestAuthentication.authenticate(saveUser("bm.super@test.invalid", UserRole.SUPER_ADMIN, null));
        mockMvc.perform(benchmarks(orgA, "")).andExpect(status().isOk());
    }

    /* ------------------------------------------------------------ seeding */

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder benchmarks(
            Organization org, String extraQuery) {
        return get("/api/organizations/" + org.getId() + "/benchmarks?pipelineId=" + pipelineId
                + extraQuery);
    }

    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.saveAndFlush(org);
    }

    private Organization saveSubOrg(String name, Organization parent) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        org.setParentOrganization(parent);
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

    private UUID insertCohort(UUID orgId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, org_id, name) VALUES (?, ?, ?)", id, orgId, name);
        return id;
    }

    private UUID insertUser(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, ?, 'Founder', 'MEMBER', 'ACTIVE', ?)
                """, id, "bm.founder." + (++emailSeq) + "@test.invalid", orgId);
        return id;
    }

    /** Assignment + EVALUATED submission + Vision evaluation; returns the submission id. */
    private UUID insertEvaluatedSubmission(UUID orgId, UUID userId, double visionScore, int daysAgo) {
        return insertSubmission(insertAssignment(orgId, userId), userId, visionScore, daysAgo);
    }

    private UUID insertAssignment(UUID orgId, UUID userId) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, orgId, userId, orgAdminA.getId());
        return assignmentId;
    }

    private UUID insertSubmission(UUID assignmentId, UUID userId, double visionScore, int daysAgo) {
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, ?, 'EVALUATED', now() - make_interval(days => ?))
                """, submissionId, assignmentId, userId, daysAgo);
        insertEval(submissionId, pillarVision, visionScore);
        return submissionId;
    }

    private void insertEval(UUID submissionId, UUID pillarId, double score) {
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label)
                VALUES (?, ?, ?, 'Strong')
                """, submissionId, pillarId, score);
    }

    private UUID insertFounderWithScore(UUID orgId, double visionScore) {
        UUID userId = insertUser(orgId);
        insertEvaluatedSubmission(orgId, userId, visionScore, 0);
        return userId;
    }

    /** {@code count} founders with one evaluated Vision score each; returns submission ids. */
    private List<UUID> seedFounders(UUID orgId, int count, double visionScore, UUID cohortId) {
        List<UUID> submissionIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID userId = insertUser(orgId);
            if (cohortId != null) {
                jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                        cohortId, userId);
            }
            submissionIds.add(insertEvaluatedSubmission(orgId, userId, visionScore, 0));
        }
        return submissionIds;
    }
}
