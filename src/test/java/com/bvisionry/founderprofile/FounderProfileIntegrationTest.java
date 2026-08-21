package com.bvisionry.founderprofile;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shared founder profile (redesign spec §2.4): one payload for admin and
 * coach, capability-gated at the door; the unified multi-type work list; the
 * FRI header math; and — load-bearing — the PRIVACY INVARIANT: raw assessment
 * answer text must never appear anywhere in the profile payload.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class FounderProfileIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Distinctive answer text that must NEVER surface in any profile payload. */
    private static final String ANSWER_CANARY = "PRIVACY-CANARY-my-cofounder-quit-last-week";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization orgA;
    private Organization orgB;
    private User admin;       // ORG_ADMIN org A
    private User adminB;      // ORG_ADMIN org B
    private User coach;       // COACH org A, granted cohort1
    private User coachUngranted; // COACH org A, no grants
    private User founder;     // MEMBER org A, in cohort1
    private User founderB;    // MEMBER org B
    /** MEMBER org A, in cohort2 — every merge shape of the Work list, one member. */
    private User merged;
    private UUID cohort1;
    private UUID cohort2;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Profile Org A");
        orgB = saveOrg("Profile Org B");
        admin = saveUser("admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        adminB = saveUser("admin.b@test.invalid", UserRole.ORG_ADMIN, orgB);
        coach = saveUser("coach.a@test.invalid", UserRole.COACH, orgA);
        coachUngranted = saveUser("coach.none@test.invalid", UserRole.COACH, orgA);
        founder = saveUser("founder.a@test.invalid", UserRole.MEMBER, orgA);
        founderB = saveUser("founder.b@test.invalid", UserRole.MEMBER, orgB);
        merged = saveUser("founder.merged@test.invalid", UserRole.MEMBER, orgA);

        cohort1 = insertCohort(orgA.getId(), "Cohort One", founder.getId());
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, NULL)
                """, orgA.getId(), coach.getId(), cohort1);

        seedProgram();
        seedExercise();
        seedCourse();
        seedAssessments();
        seedNoteAndAnnouncement();
        // A second member on a second cohort so the merge cases below cannot
        // disturb a single number the fixture above pins.
        cohort2 = insertCohort(orgA.getId(), "Cohort Two", merged.getId());
        seedMergedWorkFixture();
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    private String adminProfile(Organization org, User member) {
        return "/api/organizations/" + org.getId() + "/members/" + member.getId()
                + "/founder-profile";
    }

    @Nested
    class AdminRead {

        @Test
        void fullProfile_headerWorkNotesAnnouncements() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isOk())
                    // header (§7b: dated)
                    .andExpect(jsonPath("$.header.name", is("founder.a")))
                    .andExpect(jsonPath("$.header.email", is("founder.a@test.invalid")))
                    .andExpect(jsonPath("$.header.cohorts[0].name", is("Cohort One")))
                    .andExpect(jsonPath("$.header.cohorts[0].id", notNullValue()))
                    // FRI: latest evaluated overall. No Δ on this header — the
                    // movement number is the comparison's, not a second
                    // instrument-blind derivation.
                    .andExpect(jsonPath("$.header.friLatest", is(71.25)))
                    .andExpect(jsonPath("$.header.friDelta").doesNotExist())
                    .andExpect(jsonPath("$.header.friEvaluatedAt", notNullValue()))
                    .andExpect(jsonPath("$.header.lastActivityAt", notNullValue()))
                    // Unified work list, MERGED model (§2.4 as amended
                    // 2026-08-12): 3 program + 1 exercise + 2 assessments. The
                    // enrollment behind Course Task is folded ONTO the task row
                    // and suppressed from the org-level remainder — one piece of
                    // work, one row.
                    .andExpect(jsonPath("$.work", hasSize(6)))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM')].title",
                            hasItem("Task One")))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Task One')].status",
                            hasItem("SUBMITTED")))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Task One')].context",
                            hasItem("Cohort One · Module One")))
                    // The merged course-task row carries the real type and the
                    // enrollment's own state; the untouched lesson stays a to-do.
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Course Task')].status",
                            hasItem("COMPLETED")))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Course Task')].taskType",
                            hasItem("COURSE")))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Course Task')].progressPct",
                            hasItem(100)))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Task Two')].status",
                            hasItem(nullValue())))
                    .andExpect(jsonPath("$.work[?(@.type=='EXERCISE')].status",
                            hasItem("REVIEWED")))
                    .andExpect(jsonPath("$.work[?(@.type=='EXERCISE')].reviewedAt",
                            hasItem(notNullValue())))
                    // The task-covered enrollment does NOT also appear as a bare
                    // COURSE artifact row.
                    .andExpect(jsonPath("$.work[?(@.type=='COURSE')]", hasSize(0)))
                    .andExpect(jsonPath("$.work[?(@.type=='ASSESSMENT')].score",
                            hasItem(71.25)))
                    // latest pillar snapshot for the Growth none/pending states
                    .andExpect(jsonPath("$.pillarScores", hasSize(1)))
                    .andExpect(jsonPath("$.pillarScores[0].pillarName", is("Vision")))
                    .andExpect(jsonPath("$.pillarScores[0].maturityLabel", is("Strong")))
                    // coach notes: labeled with the coach's name, dated
                    .andExpect(jsonPath("$.notes", hasSize(1)))
                    .andExpect(jsonPath("$.notes[0].coachName", is("coach.a")))
                    .andExpect(jsonPath("$.notes[0].createdAt", notNullValue()))
                    // announcements received (cohort-scoped)
                    .andExpect(jsonPath("$.announcements", hasSize(1)))
                    .andExpect(jsonPath("$.announcements[0].cohortName", is("Cohort One")))
                    .andExpect(jsonPath("$.announcements[0].createdAt", notNullValue()));
        }

        /**
         * The pillar snapshot describes the LATEST EVALUATED submission — the
         * same one the header FRI quotes. A newer quarantined NEEDS_REVIEW run
         * (ai_failed zeros persisted "fail loud, not quiet") must not hijack
         * the bars while the header still shows the last clean score.
         */
        @Test
        void aNewerQuarantinedRunDoesNotHijackThePillarSnapshot() throws Exception {
            UUID assignment = jdbc.queryForObject(
                    "SELECT assignment_id FROM submissions WHERE user_id = ? LIMIT 1",
                    UUID.class, founder.getId());
            UUID pillar = jdbc.queryForObject(
                    "SELECT id FROM pillars WHERE name = 'Vision'", UUID.class);
            UUID quarantined = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at)
                    VALUES (?, ?, ?, 'NEEDS_REVIEW', now() + interval '1 hour')
                    """, quarantined, assignment, founder.getId());
            jdbc.update("""
                    INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage,
                                                    maturity_label, ai_failed)
                    VALUES (?, ?, 0.00, 'Failed', true)
                    """, quarantined, pillar);

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.friLatest", is(71.25)))
                    .andExpect(jsonPath("$.pillarScores", hasSize(1)))
                    .andExpect(jsonPath("$.pillarScores[0].maturityLabel", is("Strong")));
        }

        @Test
        void foreignAdminIsRejected_andForeignMemberIs404() throws Exception {
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isForbidden());

            // Own org path but another tenant's member id → 404, no leak.
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, founderB)))
                    .andExpect(status().isNotFound());

            // A non-member (the coach) is not a founder profile either.
            mockMvc.perform(get(adminProfile(orgA, coach)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void memberAndCoachCannotUseTheAdminDoor() throws Exception {
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isForbidden());

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class CoachRead {

        @Test
        void grantedCoachGetsTheSameShape() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.name", is("founder.a")))
                    .andExpect(jsonPath("$.header.friLatest", is(71.25)))
                    .andExpect(jsonPath("$.work", hasSize(6)))
                    .andExpect(jsonPath("$.notes[0].coachName", is("coach.a")));
        }

        @Test
        void ungrantedCoachIs404_memberIs403() throws Exception {
            TestAuthentication.authenticate(coachUngranted);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/profile"))
                    .andExpect(status().isNotFound());

            // Members have NO route to the profile or the notes it carries.
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/profile"))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * The MERGED work-item model (spec §2.4 as amended 2026-08-12): a cohort
     * task and the artifact its {@code program_task_id} tags are ONE piece of
     * work and therefore ONE row — the task row carries the artifact's type,
     * status and link target, and the artifact drops out of the org-level
     * remainder. Whatever carries no tag stays its own row with a null
     * {@code cohortId}: the Direct bucket.
     */
    @Nested
    class MergedWorkItems {

        @Test
        void everyTaskIsOneRowAndEveryUntaggedArtifactKeepsItsOwn() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    // 4 cohort tasks + 3 untagged artifacts + the REMOVED course
                    // (which survives its own merge — see below).
                    .andExpect(jsonPath("$.work", hasSize(8)))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM')]", hasSize(4)))
                    .andExpect(jsonPath("$.work[?(@.cohortId == null)]", hasSize(4)));
        }

        /**
         * The exercise merge: the task row IS the exercise. Its status, review
         * stamp and §4 quality tag ride on the PROGRAM row, and the assignment
         * that supplied them is gone from the Direct bucket — one piece of work,
         * one row.
         */
        @Test
        void aTaggedExerciseRidesOnItsTaskRowAndLeavesNoDuplicate() throws Exception {
            TestAuthentication.authenticate(admin);
            String program = "$.work[?(@.type=='PROGRAM' && @.title=='Exercise Task')]";
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(program, hasSize(1)))
                    .andExpect(jsonPath(program + ".taskType", hasItem("EXERCISE")))
                    .andExpect(jsonPath(program + ".cohortId", hasItem(cohort2.toString())))
                    .andExpect(jsonPath(program + ".status", hasItem("REVIEWED")))
                    .andExpect(jsonPath(program + ".submittedAt", hasItem(notNullValue())))
                    .andExpect(jsonPath(program + ".reviewedAt", hasItem(notNullValue())))
                    // §4: the reviewer's tag snapshot travels with the merged row.
                    .andExpect(jsonPath(program + ".qualityTagLabel", hasItem("Exemplary")))
                    .andExpect(jsonPath(program + ".qualityTaggedAt", hasItem(notNullValue())))
                    // The link target is the ASSIGNMENT, not the task.
                    .andExpect(jsonPath(program + ".artifactId",
                            hasItem(mergedExerciseAssignment.toString())))
                    // …and only the UNTAGGED assignment is left in Direct.
                    .andExpect(jsonPath("$.work[?(@.type=='EXERCISE')]", hasSize(1)))
                    .andExpect(jsonPath("$.work[?(@.type=='EXERCISE')].refId",
                            hasItem(directExerciseAssignment.toString())))
                    .andExpect(jsonPath("$.work[?(@.type=='EXERCISE')].cohortId",
                            hasItem(nullValue())));
        }

        /**
         * Surveys have no artifact read of their own — the response rides on the
         * task row through {@code survey_responses.program_task_id} (V173), which
         * is also the only thing that makes "done" cohort-scoped.
         */
        @Test
        void aTaggedSurveyResponseMakesItsTaskRowSubmitted() throws Exception {
            TestAuthentication.authenticate(admin);
            String program = "$.work[?(@.type=='PROGRAM' && @.title=='Survey Task')]";
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(program, hasSize(1)))
                    .andExpect(jsonPath(program + ".taskType", hasItem("SURVEY")))
                    .andExpect(jsonPath(program + ".status", hasItem("SUBMITTED")))
                    .andExpect(jsonPath(program + ".submittedAt", hasItem(notNullValue())))
                    // refId is the TASK (the row's own identity); the response is
                    // the artifact the row links out to.
                    .andExpect(jsonPath(program + ".refId", hasItem(mergedSurveyTask.toString())))
                    .andExpect(jsonPath(program + ".artifactId",
                            hasItem(mergedSurveyResponse.toString())));
        }

        /**
         * The {@code taskPipelines} rule: an ASSESSMENT task the member has an
         * assignment for but has NEVER started has no submission to merge, so
         * nothing suppresses the bare assignment by id — it is suppressed by
         * PIPELINE instead. Without that rule the same untouched assessment
         * would read "To do" twice, once on the journey and once in Direct.
         */
        @Test
        void aNeverStartedAssessmentTaskDoesNotAlsoAppearInTheDirectBucket() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Assessment Task')]",
                            hasSize(1)))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Assessment Task')].status",
                            hasItem(nullValue())))
                    // Its pipeline appears nowhere else…
                    .andExpect(jsonPath("$.work[?(@.title=='Milestone Pipeline')]", hasSize(0)))
                    // …while the untagged pipeline keeps its own Direct row.
                    .andExpect(jsonPath("$.work[?(@.type=='ASSESSMENT')]", hasSize(1)))
                    .andExpect(jsonPath("$.work[?(@.type=='ASSESSMENT')].title",
                            hasItem("Direct Pipeline")))
                    .andExpect(jsonPath("$.work[?(@.type=='ASSESSMENT')].refId",
                            hasItem(directAssessmentAssignment.toString())));
        }

        /**
         * The pre-spine intake shape: an EVALUATED sitting nobody tagged. The
         * journey shows it on the BASELINE band (the earliest-evaluated
         * fallback), so the Work tab's PROGRAM row must pick it up too — and
         * the Direct bucket must not show the same sitting a second time.
         */
        @Test
        void anAdoptableUntaggedSittingRidesOnItsBaselineTaskRow_notInDirect() throws Exception {
            UUID sitting = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO submissions (id, assignment_id, user_id, status,
                                             submitted_at, evaluated_at)
                    VALUES (?, ?, ?, 'EVALUATED', now(), now())
                    """, sitting, neverStartedAssignment, merged.getId());

            TestAuthentication.authenticate(admin);
            String program = "$.work[?(@.type=='PROGRAM' && @.title=='Assessment Task')]";
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.work", hasSize(8)))
                    .andExpect(jsonPath(program, hasSize(1)))
                    .andExpect(jsonPath(program + ".status", hasItem("EVALUATED")))
                    .andExpect(jsonPath(program + ".submissionId", hasItem(sitting.toString())))
                    // …and Direct still holds only the untagged twin pipeline.
                    .andExpect(jsonPath("$.work[?(@.type=='ASSESSMENT')]", hasSize(1)))
                    .andExpect(jsonPath("$.work[?(@.type=='ASSESSMENT')].title",
                            hasItem("Direct Pipeline")));
        }

        /**
         * The audit-trail exception ({@code c.removed() || !mergedCourses…}):
         * an admin's per-member removal keeps its row even though a cohort task
         * covers the same course. Losing it would erase the only record that the
         * member was ever put on the course and taken off it.
         */
        @Test
        void aRemovedCourseSurvivesTheMergeAsTheAuditTrail() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    // the merged task row…
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Course Task')]",
                            hasSize(1)))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Course Task')].taskType",
                            hasItem("COURSE")))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Course Task')].artifactId",
                            hasItem(removedCourseId.toString())))
                    // …AND the removal record, flagged rather than dropped.
                    .andExpect(jsonPath("$.work[?(@.title=='Removed Course')]", hasSize(1)))
                    .andExpect(jsonPath("$.work[?(@.title=='Removed Course')].type",
                            hasItem("COURSE")))
                    .andExpect(jsonPath("$.work[?(@.title=='Removed Course')].removed",
                            hasItem(true)))
                    .andExpect(jsonPath("$.work[?(@.title=='Removed Course')].cohortId",
                            hasItem(nullValue())));
        }

        @Test
        void aSelfEnrolledCourseWithNoTaskStaysDirect() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.work[?(@.title=='Self Course')]", hasSize(1)))
                    .andExpect(jsonPath("$.work[?(@.title=='Self Course')].type", hasItem("COURSE")))
                    .andExpect(jsonPath("$.work[?(@.title=='Self Course')].cohortId",
                            hasItem(nullValue())))
                    .andExpect(jsonPath("$.work[?(@.title=='Self Course')].courseSource",
                            hasItem("SELF")))
                    .andExpect(jsonPath("$.work[?(@.title=='Self Course')].removed",
                            hasItem(false)))
                    // A course with no lessons has no denominator, so
                    // CourseProgressSql keeps the STORED number rather than
                    // reporting a freshly-computed 0%.
                    .andExpect(jsonPath("$.work[?(@.title=='Self Course')].progressPct",
                            hasItem(25)));
        }
    }

    /**
     * V176 grain reporting. A {@code coach_assignments} row is a whole-cohort
     * grant (cohortId set), a direct grant (memberId set) or the org-wide grant
     * (BOTH null) — and the three are independent flags on the way out. Before
     * V176 a null {@code cohortId} was taken to MEAN "direct", which turns the
     * house coach into a personal one the moment the third grain exists.
     */
    @Nested
    class CoachGrainGrouping {

        @Test
        void eachGrainIsReportedAsItself_andSeveralAtOnceAreAllTrue() throws Exception {
            User house = saveUser("coach.house@test.invalid", UserRole.COACH, orgA);
            User direct = saveUser("coach.direct@test.invalid", UserRole.COACH, orgA);
            User everything = saveUser("coach.all@test.invalid", UserRole.COACH, orgA);
            grant(house, null, null);
            grant(direct, null, founder);
            grant(everything, null, null);
            grant(everything, cohort1, null);
            grant(everything, null, founder);

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isOk())
                    // ordered by coach name: coach.a, coach.all, coach.direct, coach.house
                    .andExpect(jsonPath("$.header.coaches", hasSize(4)))
                    // (a) the seeded cohort grant
                    .andExpect(jsonPath("$.header.coaches[0].name", is("coach.a")))
                    .andExpect(jsonPath("$.header.coaches[0].cohortIds",
                            is(java.util.List.of(cohort1.toString()))))
                    .andExpect(jsonPath("$.header.coaches[0].direct", is(false)))
                    .andExpect(jsonPath("$.header.coaches[0].orgWide", is(false)))
                    // (b) all three grains at once — grouped into ONE ref
                    .andExpect(jsonPath("$.header.coaches[1].name", is("coach.all")))
                    .andExpect(jsonPath("$.header.coaches[1].cohortIds",
                            is(java.util.List.of(cohort1.toString()))))
                    .andExpect(jsonPath("$.header.coaches[1].direct", is(true)))
                    .andExpect(jsonPath("$.header.coaches[1].orgWide", is(true)))
                    // (c) direct only
                    .andExpect(jsonPath("$.header.coaches[2].name", is("coach.direct")))
                    .andExpect(jsonPath("$.header.coaches[2].cohortIds", hasSize(0)))
                    .andExpect(jsonPath("$.header.coaches[2].direct", is(true)))
                    .andExpect(jsonPath("$.header.coaches[2].orgWide", is(false)))
                    // (d) org-wide only — the regression: a null cohortId is NOT
                    // "direct", it is the widest grain there is.
                    .andExpect(jsonPath("$.header.coaches[3].name", is("coach.house")))
                    .andExpect(jsonPath("$.header.coaches[3].cohortIds", hasSize(0)))
                    .andExpect(jsonPath("$.header.coaches[3].direct", is(false)))
                    .andExpect(jsonPath("$.header.coaches[3].orgWide", is(true)));
        }

        /** The org-wide grain is tenant-bounded: it reaches every member of ITS org only. */
        @Test
        void theHouseCoachCoversEveryMemberOfTheirOwnOrgAndNoOther() throws Exception {
            User house = saveUser("coach.house@test.invalid", UserRole.COACH, orgA);
            grant(house, null, null);

            TestAuthentication.authenticate(admin);
            // a member of a DIFFERENT cohort, with no grant naming them at all
            mockMvc.perform(get(adminProfile(orgA, merged)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.coaches", hasSize(1)))
                    .andExpect(jsonPath("$.header.coaches[0].name", is("coach.house")))
                    .andExpect(jsonPath("$.header.coaches[0].orgWide", is(true)))
                    .andExpect(jsonPath("$.header.coaches[0].direct", is(false)));

            // …and org B's founder is reached by nobody.
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(adminProfile(orgB, founderB)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.coaches", hasSize(0)));
        }

        /**
         * The grant says nothing about the COACH row it names (see
         * {@code CoachAccess.VISIBLE_COACH_PREDICATE}'s javadoc), so the header
         * query pins org + role + status itself: a suspended coach, one demoted
         * out of the role while the grant survives, and another tenant's coach
         * named by a hand-written grant all drop off.
         */
        @Test
        void aSuspendedDemotedOrForeignCoachIsNotOnTheHeader() throws Exception {
            User demoted = saveUser("coach.demoted@test.invalid", UserRole.COACH, orgA);
            User foreign = saveUser("coach.foreign@test.invalid", UserRole.COACH, orgB);
            grant(demoted, cohort1, null);
            grant(foreign, cohort1, null);
            jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", coach.getId());
            jdbc.update("UPDATE users SET role = 'MEMBER' WHERE id = ?", demoted.getId());

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.coaches", hasSize(0)));
        }

        private void grant(User coachUser, UUID cohortId, User member) {
            jdbc.update("""
                    INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                    VALUES (?, ?, ?, ?)
                    """, orgA.getId(), coachUser.getId(), cohortId,
                    member == null ? null : member.getId());
        }
    }

    /**
     * Spec §2.4 privacy invariant: scores, labels and maturity only — the raw
     * answer text a founder typed into the assessment player must never reach
     * an admin or coach through the profile, its journey, or its comparison.
     */
    @Nested
    class PrivacyInvariant {

        @Test
        void noAnswerTextInAnyProfileShapedPayload() throws Exception {
            TestAuthentication.authenticate(admin);
            String adminBody = mockMvc.perform(get(adminProfile(orgA, founder)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(adminBody).doesNotContain(ANSWER_CANARY).doesNotContain("responseText");

            String journeyBody = mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/members/" + founder.getId() + "/journey"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(journeyBody).doesNotContain(ANSWER_CANARY);

            String adminComparison = mockMvc.perform(get("/api/organizations/" + orgA.getId()
                            + "/members/" + founder.getId() + "/growth-comparison"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(adminComparison).doesNotContain(ANSWER_CANARY);

            TestAuthentication.authenticate(coach);
            String coachBody = mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId()
                            + "/profile"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(coachBody).doesNotContain(ANSWER_CANARY).doesNotContain("responseText");

            String coachJourney = mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId()
                            + "/journey"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(coachJourney).doesNotContain(ANSWER_CANARY);

            String coachComparison = mockMvc.perform(get("/api/v1/coach/founders/"
                            + founder.getId() + "/comparison"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(coachComparison).doesNotContain(ANSWER_CANARY);
        }
    }

    /** The read-only journey + admin growth-comparison doors the profile tabs use. */
    @Nested
    class CompanionReads {

        @Test
        void adminAndCoachReadTheMembersJourney() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/members/"
                            + founder.getId() + "/journey"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modules", hasSize(1)))
                    .andExpect(jsonPath("$.modules[0].name", is("Module One")))
                    // Task One submitted + the completed course task; Task Two open.
                    .andExpect(jsonPath("$.progress.done", is(2)))
                    .andExpect(jsonPath("$.progress.total", is(3)));

            // Foreign member id under my own org → 404. `journeyOfMember`
            // resolves by enrolment alone and TREATS orgId as the viewed
            // member's org, so without the controller's requireMemberOf this
            // answers 200 with org B's founder's journey.
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/members/"
                            + founderB.getId() + "/journey"))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/journey"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modules", hasSize(1)));

            // Explicit cohort selection: the member's own cohort works, a
            // cohort they're not enrolled in (or a foreign org's) is a 404.
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId()
                            + "/journey?cohortId=" + cohort1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cohortId", is(cohort1.toString())));
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId()
                            + "/journey?cohortId=" + UUID.randomUUID()))
                    .andExpect(status().isNotFound());

            // Ungranted coach: 404. Member: 403 at the gate.
            TestAuthentication.authenticate(coachUngranted);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/journey"))
                    .andExpect(status().isNotFound());
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/v1/coach/founders/" + founder.getId() + "/journey"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void adminGrowthComparisonMirrorsTheMemberLifecycle() throws Exception {
            // No designated pair → "none", never a teased report; trajectory
            // still carries both evaluated submissions.
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/members/"
                            + founder.getId() + "/growth-comparison"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is("none")))
                    .andExpect(jsonPath("$.comparison", nullValue()))
                    .andExpect(jsonPath("$.trajectory", hasSize(2)));

            // Foreign member id under my org → 404.
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/members/"
                            + founderB.getId() + "/growth-comparison"))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(founder);
            mockMvc.perform(get("/api/organizations/" + orgA.getId() + "/members/"
                            + founder.getId() + "/growth-comparison"))
                    .andExpect(status().isForbidden());
        }
    }

    /* ------------------------------------------------------------ seeding */

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

    /** Module One in cohort1: Task One SUBMITTED, Task Two untouched. */
    private void seedProgram() {
        UUID moduleId = UUID.randomUUID();
        this.moduleOneId = moduleId;
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, assign_mode, lock_mode)
                VALUES (?, ?, 'Module One', 'ALL', 'UNLOCKED')
                """, moduleId, cohort1);
        UUID task1 = UUID.randomUUID();
        jdbc.update("INSERT INTO program_tasks (id, module_id, name, status) VALUES (?, ?, 'Task One', 'LIVE')",
                task1, moduleId);
        jdbc.update("INSERT INTO program_tasks (module_id, name, status) VALUES (?, 'Task Two', 'LIVE')",
                moduleId);
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, submitted_at)
                VALUES (?, ?, 'SUBMITTED', now())
                """, task1, founder.getId());
    }

    /** One exercise assignment, submitted and reviewed (reviewed_at set — §7b). */
    private void seedExercise() {
        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by)
                VALUES (?, 'Competitor Analysis', 'PUBLISHED', ?)
                """, templateId, admin.getId());
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, templateId, orgA.getId(), founder.getId(), admin.getId());
        jdbc.update("""
                INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at, reviewed_at)
                VALUES (?, ?, 'REVIEWED', now(), now())
                """, assignmentId, founder.getId());
    }

    /** One course enrolment whose newest ENROLLED ledger row names pillar "Vision" → AI source. */
    private void seedCourse() {
        UUID courseId = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title, state) VALUES (?, ?, ?, 'Pricing Foundations', 'PUBLISHED')",
                courseId, orgA.getId(), "pricing-" + courseId);
        // V168: the engine stamps source on its own inserts (spec §3), so the
        // seed does too — otherwise this row would read SELF and the Work tab
        // would honestly report a self-enrolment.
        jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, progress_pct, source, status, completed_at)
                VALUES (?, ?, 100, 'AI_SUGGESTED', 'COMPLETED', now())
                """, founder.getId(), courseId);
        // A COHORT TASK over that same finished course: its done-ness lives in
        // `enrollment`, never in `program_submissions` — the Work tab must read
        // the shared done authority, not the (absent) submission row.
        jdbc.update("""
                INSERT INTO program_tasks (module_id, name, status, position, task_type, ref_id)
                VALUES (?, 'Course Task', 'LIVE', 2, 'COURSE', ?)
                """, moduleOneId, courseId);
        // Ledger row wants the pillar + submission seeded in seedAssessments —
        // insert after those exist (see seed order): use the latest submission.
        this.pendingCourseId = courseId;
    }

    private UUID pendingCourseId;
    private UUID moduleOneId;

    /**
     * Two evaluated submissions: baseline 50.00, latest 71.25 (Δ +21.25).
     * The latest carries the pillar evaluation AND an answers row with the
     * privacy canary text.
     */
    private void seedAssessments() {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status) VALUES (?, 'Founder Mindset', 'PUBLISHED')",
                pipelineId);
        UUID pillarId = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name) VALUES (?, ?, 'Vision')",
                pillarId, pipelineId);
        UUID questionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO questions (id, pillar_id, type, prompt_text)
                VALUES (?, ?, 'FREE_TEXT', 'How is your cofounder relationship?')
                """, questionId, pillarId);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, orgA.getId(), founder.getId(), admin.getId());

        UUID baseline = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now() - interval '30 days', now() - interval '30 days')
                """, baseline, assignmentId, founder.getId());
        jdbc.update("INSERT INTO overall_summaries (submission_id, overall_score_percentage) VALUES (?, 50.00)",
                baseline);

        UUID latest = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now(), now())
                """, latest, assignmentId, founder.getId());
        jdbc.update("INSERT INTO overall_summaries (submission_id, overall_score_percentage) VALUES (?, 71.25)",
                latest);
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label)
                VALUES (?, ?, 72.50, 'Strong')
                """, latest, pillarId);
        jdbc.update("""
                INSERT INTO answers (submission_id, question_id, response_text)
                VALUES (?, ?, ?)
                """, latest, questionId, ANSWER_CANARY);

        jdbc.update("""
                INSERT INTO auto_enrolments (user_id, course_id, submission_id, pillar_id,
                                             band_position, outcome)
                VALUES (?, ?, ?, ?, 0, 'ENROLLED')
                """, founder.getId(), pendingCourseId, latest, pillarId);
    }

    /* ---------------------------------------------- the merged Work fixture */

    private UUID mergedExerciseTask;
    private UUID mergedExerciseAssignment;
    private UUID mergedSurveyTask;
    private UUID mergedSurveyResponse;
    private UUID neverStartedTask;
    private UUID neverStartedPipeline;
    private UUID neverStartedAssignment;
    private UUID removedCourseTask;
    private UUID removedCourseId;
    private UUID selfCourseId;
    private UUID directExerciseAssignment;
    private UUID directAssessmentAssignment;

    /**
     * Cohort Two, one module, one task of every mergeable type — each with the
     * artifact its {@code program_task_id} tags (V164/V173) — plus the untagged
     * twins that must stay in the Direct bucket:
     *
     * <ul>
     *   <li>EXERCISE task ← a tagged assignment, REVIEWED and quality-tagged;</li>
     *   <li>SURVEY task ← a tagged PROGRAM_TASK response;</li>
     *   <li>ASSESSMENT task ← an assignment the member never started;</li>
     *   <li>COURSE task ← an enrolment an admin later REMOVED (override row).</li>
     * </ul>
     */
    private void seedMergedWorkFixture() {
        UUID moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, assign_mode, lock_mode)
                VALUES (?, ?, 'Merge Module', 'ALL', 'UNLOCKED')
                """, moduleId, cohort2);

        // ---- EXERCISE: task ← tagged assignment (submitted, reviewed, tagged)
        UUID template = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by)
                VALUES (?, 'Pricing Teardown', 'PUBLISHED', ?)
                """, template, admin.getId());
        mergedExerciseTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type, ref_id)
                VALUES (?, ?, 'Exercise Task', 'LIVE', 0, 'EXERCISE', ?)
                """, mergedExerciseTask, moduleId, template);
        mergedExerciseAssignment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id,
                                                  assigned_by, program_task_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, mergedExerciseAssignment, template, orgA.getId(), merged.getId(),
                admin.getId(), mergedExerciseTask);
        jdbc.update("""
                INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at,
                                                  reviewed_at, quality_tag_label, quality_tagged_at)
                VALUES (?, ?, 'REVIEWED', now(), now(), 'Exemplary', now())
                """, mergedExerciseAssignment, merged.getId());

        // ---- SURVEY: task ← tagged PROGRAM_TASK response
        UUID surveyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO surveys (id, name, status, created_by)
                VALUES (?, 'Mid-programme Pulse', 'PUBLISHED', ?)
                """, surveyId, admin.getId());
        mergedSurveyTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type, ref_id)
                VALUES (?, ?, 'Survey Task', 'LIVE', 1, 'SURVEY', ?)
                """, mergedSurveyTask, moduleId, surveyId);
        mergedSurveyResponse = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO survey_responses (id, survey_id, source, respondent_user_id,
                                              program_task_id, submitted_at)
                VALUES (?, ?, 'PROGRAM_TASK', ?, ?, now())
                """, mergedSurveyResponse, surveyId, merged.getId(), mergedSurveyTask);

        // ---- ASSESSMENT: task over a pipeline the member has never started
        neverStartedPipeline = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status) VALUES (?, 'Milestone Pipeline', 'PUBLISHED')",
                neverStartedPipeline);
        neverStartedTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type, ref_id,
                                           milestone_role)
                VALUES (?, ?, 'Assessment Task', 'LIVE', 2, 'ASSESSMENT', ?, 'BASELINE')
                """, neverStartedTask, moduleId, neverStartedPipeline);
        neverStartedAssignment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, neverStartedAssignment, neverStartedPipeline, orgA.getId(), merged.getId(),
                admin.getId());

        // ---- COURSE: task over a course the admin later REMOVED for this member
        removedCourseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO course (id, org_id, slug, title, state)
                VALUES (?, ?, ?, 'Removed Course', 'PUBLISHED')
                """, removedCourseId, orgA.getId(), "removed-" + removedCourseId);
        jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, status, progress_pct, source)
                VALUES (?, ?, 'CANCELLED', 40, 'DIRECT')
                """, merged.getId(), removedCourseId);
        jdbc.update("""
                INSERT INTO enrolment_overrides (user_id, course_id, removed_by, reason)
                VALUES (?, ?, ?, 'Covered by the workshop instead.')
                """, merged.getId(), removedCourseId, admin.getId());
        removedCourseTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type, ref_id)
                VALUES (?, ?, 'Course Task', 'LIVE', 3, 'COURSE', ?)
                """, removedCourseTask, moduleId, removedCourseId);

        // ---- the Direct bucket: the same three shapes with NO task tag
        UUID directTemplate = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by)
                VALUES (?, 'Direct Worksheet', 'PUBLISHED', ?)
                """, directTemplate, admin.getId());
        directExerciseAssignment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, directExerciseAssignment, directTemplate, orgA.getId(), merged.getId(),
                admin.getId());

        UUID directPipeline = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status) VALUES (?, 'Direct Pipeline', 'PUBLISHED')",
                directPipeline);
        directAssessmentAssignment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, directAssessmentAssignment, directPipeline, orgA.getId(), merged.getId(),
                admin.getId());

        selfCourseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO course (id, org_id, slug, title, state)
                VALUES (?, ?, ?, 'Self Course', 'PUBLISHED')
                """, selfCourseId, orgA.getId(), "self-" + selfCourseId);
        jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, status, progress_pct, source)
                VALUES (?, ?, 'ACTIVE', 25, 'SELF')
                """, merged.getId(), selfCourseId);
    }

    private void seedNoteAndAnnouncement() {
        jdbc.update("""
                INSERT INTO coach_notes (org_id, coach_id, member_id, body)
                VALUES (?, ?, ?, 'Strong week — pricing exercise shows real depth.')
                """, orgA.getId(), coach.getId(), founder.getId());
        jdbc.update("""
                INSERT INTO announcements (id, org_id, cohort_id, author_id, body, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Demo day moved to Friday.', now(), now())
                """, UUID.randomUUID(), orgA.getId(), cohort1, admin.getId());
    }
}
