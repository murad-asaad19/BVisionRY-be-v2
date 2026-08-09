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
    private UUID cohort1;

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
                    // FRI: baseline 50.00 → latest 71.25, Δ-so-far +21.25
                    .andExpect(jsonPath("$.header.friLatest", is(71.25)))
                    .andExpect(jsonPath("$.header.friDelta", is(21.25)))
                    .andExpect(jsonPath("$.header.friEvaluatedAt", notNullValue()))
                    .andExpect(jsonPath("$.header.lastActivityAt", notNullValue()))
                    // unified work list: 2 program + 1 exercise + 1 course + 2 assessments
                    .andExpect(jsonPath("$.work", hasSize(6)))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM')].title",
                            hasItem("Task One")))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Task One')].status",
                            hasItem("SUBMITTED")))
                    .andExpect(jsonPath("$.work[?(@.type=='PROGRAM' && @.title=='Task One')].context",
                            hasItem("Cohort One · Module One")))
                    .andExpect(jsonPath("$.work[?(@.type=='EXERCISE')].status",
                            hasItem("REVIEWED")))
                    .andExpect(jsonPath("$.work[?(@.type=='EXERCISE')].reviewedAt",
                            hasItem(notNullValue())))
                    // course source: today's reality — auto-enrolment pillar = AI
                    .andExpect(jsonPath("$.work[?(@.type=='COURSE')].courseSource",
                            hasItem("AI")))
                    .andExpect(jsonPath("$.work[?(@.type=='COURSE')].context",
                            hasItem("Vision")))
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
                    .andExpect(jsonPath("$.progress.done", is(1)))
                    .andExpect(jsonPath("$.progress.total", is(2)));

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
        jdbc.update("INSERT INTO cohorts (id, org_id, name) VALUES (?, ?, ?)", id, orgId, name);
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", id, memberId);
        return id;
    }

    /** Module One in cohort1: Task One SUBMITTED, Task Two untouched. */
    private void seedProgram() {
        UUID moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, org_id, cohort_id, name, assign_mode, lock_mode)
                VALUES (?, ?, ?, 'Module One', 'ALL', 'UNLOCKED')
                """, moduleId, orgA.getId(), cohort1);
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
        jdbc.update("INSERT INTO enrollment (user_id, course_id, progress_pct) VALUES (?, ?, 38)",
                founder.getId(), courseId);
        // Ledger row wants the pillar + submission seeded in seedAssessments —
        // insert after those exist (see seed order): use the latest submission.
        this.pendingCourseId = courseId;
    }

    private UUID pendingCourseId;

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
