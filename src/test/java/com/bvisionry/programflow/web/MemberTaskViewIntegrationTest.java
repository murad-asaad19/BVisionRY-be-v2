package com.bvisionry.programflow.web;

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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The staff LESSON viewer ({@code MemberTaskViewController} → {@code
 * MyProgramService.playerOfMember}, spec §2.4): the founder profile's Work tab
 * opens a member's task in the EXACT player the member sees, read-only.
 *
 * <p>Three rules carry it and each is asserted by falsification: {@code readOnly}
 * is forced regardless of what the member's own player would say; LESSON is the
 * only type with an in-place form, so anything else is refused rather than
 * half-rendered; and the two doors have DIFFERENT gates — the org guard stack for
 * admins, {@link com.bvisionry.common.coachaccess.CoachAccess} for coaches —
 * over one service that trusts neither and re-checks enrolment itself.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class MemberTaskViewIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Assessment answer text — §2.4 says it may never reach staff. */
    private static final String ANSWER_CANARY = "PRIVACY-CANARY-i-nearly-shut-the-company";
    /** The member's own PROGRAM-task answer — this viewer's whole reason to exist. */
    private static final String TASK_ANSWER = "We charge per seat, billed annually.";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization orgA;
    private Organization orgB;
    private User admin;         // ORG_ADMIN org A
    private User adminB;        // ORG_ADMIN org B
    private User coachGranted;  // COACH org A, whole-cohort grant
    private User coachOutside;  // COACH org A, no grant at all
    private User founder;       // MEMBER org A, in the cohort, answered the lesson
    private User outsider;      // MEMBER org A, NOT in the cohort
    private User founderB;      // MEMBER org B, in the SAME (shared) cohort
    private UUID cohort;
    private UUID lessonTask;
    private UUID courseTask;    // a non-LESSON task on the same module
    private UUID draftTask;
    private UUID fieldId;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Task View Org A");
        orgB = saveOrg("Task View Org B");
        admin = saveUser("admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        adminB = saveUser("admin.b@test.invalid", UserRole.ORG_ADMIN, orgB);
        coachGranted = saveUser("coach.granted@test.invalid", UserRole.COACH, orgA);
        coachOutside = saveUser("coach.outside@test.invalid", UserRole.COACH, orgA);
        founder = saveUser("founder.a@test.invalid", UserRole.MEMBER, orgA);
        outsider = saveUser("founder.out@test.invalid", UserRole.MEMBER, orgA);
        founderB = saveUser("founder.b@test.invalid", UserRole.MEMBER, orgB);

        cohort = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Growth Cohort', 'LAUNCHED')",
                cohort);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohort,
                orgA.getId());
        // The cohort spans orgs (V171) — org B's founder sits on the same roster.
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohort,
                orgB.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohort,
                founder.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohort,
                founderB.getId());
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, NULL)
                """, orgA.getId(), coachGranted.getId(), cohort);

        seedBoard();
        seedAssessmentAnswer();
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Nested
    class BothDoors {

        @Test
        void theMembersOwnAnswersComeBackAndReadOnlyIsForcedOn() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminPlayer(orgA, founder, lessonTask)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId", is(lessonTask.toString())))
                    .andExpect(jsonPath("$.taskName", is("Pricing Lesson")))
                    .andExpect(jsonPath("$.moduleName", is("Module One")))
                    // 1-based over paced modules, as the member's own player numbers it
                    .andExpect(jsonPath("$.stageNumber", is(1)))
                    // the form definition, so staff read the answer in context
                    .andExpect(jsonPath("$.fields", hasSize(1)))
                    .andExpect(jsonPath("$.fields[0].id", is(fieldId.toString())))
                    .andExpect(jsonPath("$.fields[0].type", is("LONG")))
                    .andExpect(jsonPath("$.answers['" + fieldId + "']", is(TASK_ANSWER)))
                    .andExpect(jsonPath("$.status", is("SUBMITTED")))
                    .andExpect(jsonPath("$.savedAt", notNullValue()))
                    .andExpect(jsonPath("$.submittedAt", notNullValue()))
                    // The rule: staff NEVER get a writable player, whatever the
                    // member's own cohort state would say.
                    .andExpect(jsonPath("$.readOnly", is(true)));
        }

        @Test
        void theGrantedCoachGetsTheIdenticalPayload() throws Exception {
            TestAuthentication.authenticate(admin);
            String adminBody = mockMvc.perform(get(adminPlayer(orgA, founder, lessonTask)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            TestAuthentication.authenticate(coachGranted);
            String coachBody = mockMvc.perform(get(coachPlayer(founder, lessonTask)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.readOnly", is(true)))
                    .andReturn().getResponse().getContentAsString();

            // One payload for both roles (§2.4) — capability differences are
            // client-side, so any divergence here is a drift bug.
            assertThat(coachBody).isEqualTo(adminBody);
        }

        /**
         * LESSON is the only type with an in-place form; everything else has its
         * own review surface. {@code requireLesson} throws
         * {@code BadRequestException}, which the global handler maps to 400 —
         * pinned because "400 on a real, live, correctly-owned task" is a
         * surprising surface a refactor could silently turn into a 404.
         */
        @Test
        void aNonLessonTaskIsRefusedAsABadRequestOnBothDoors() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminPlayer(orgA, founder, courseTask)))
                    .andExpect(status().isBadRequest());

            TestAuthentication.authenticate(coachGranted);
            mockMvc.perform(get(coachPlayer(founder, courseTask)))
                    .andExpect(status().isBadRequest());
        }

        /**
         * The service's own contribution, independent of either gate: the member
         * must be enrolled in the TASK's cohort. A task id that exists but is
         * not the member's reads exactly like one that does not exist.
         */
        @Test
        void aTaskOutsideTheMembersCohortsIsAbsent() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminPlayer(orgA, outsider, lessonTask)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(adminPlayer(orgA, founder, UUID.randomUUID())))
                    .andExpect(status().isNotFound());
            // A DRAFT task is the builder's business and does not exist here.
            mockMvc.perform(get(adminPlayer(orgA, founder, draftTask)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Gates {

        @Test
        void aForeignOrgAdminNeverReachesTheService() throws Exception {
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(adminPlayer(orgA, founder, lessonTask)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aCoachOutsideTheUnionIs404_whileAGrantedCoachIs200() throws Exception {
            TestAuthentication.authenticate(coachOutside);
            mockMvc.perform(get(coachPlayer(founder, lessonTask)))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(coachGranted);
            mockMvc.perform(get(coachPlayer(founder, lessonTask)))
                    .andExpect(status().isOk());
        }

        @Test
        void aMemberHasNoRouteToAnybodysPlayerThroughEitherStaffDoor() throws Exception {
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get(adminPlayer(orgA, founder, lessonTask)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(coachPlayer(founder, lessonTask)))
                    .andExpect(status().isForbidden());
        }

        /**
         * Tenancy re-anchor: the class guard proves the CALLER may act in
         * this org, never that the MEMBER named in the path belongs to it. A
         * cohort spans orgs since V171, so without the re-anchor an org-A admin
         * could read another tenant's founder's lesson answers through org A's
         * own URL. Both doors refuse it: the org door via
         * {@code OrgMemberAccess.requireMemberOf}, the coach door via
         * {@code CoachAccess}, which pins the founder to the coach's own org.
         */
        @Test
        void anotherTenantsMemberOnAsharedCohortIsNotReadableThroughEitherDoor()
                throws Exception {
            jdbc.update("""
                    INSERT INTO program_submissions (task_id, user_id, status, answers, submitted_at)
                    VALUES (?, ?, 'SUBMITTED', ?::jsonb, now())
                    """, lessonTask, founderB.getId(),
                    "{\"" + fieldId + "\": \"Org B's private answer.\"}");

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminPlayer(orgA, founderB, lessonTask)))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(coachGranted);
            mockMvc.perform(get(coachPlayer(founderB, lessonTask)))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * §2.4: this viewer exists to show PROGRAM-task answers — member-authored
     * work a coach is meant to read — and must not become a second route to the
     * assessment answer text the profile is careful never to carry.
     */
    @Nested
    class PrivacyInvariant {

        @Test
        void programAnswersAreReturnedAndAssessmentAnswersAreNot() throws Exception {
            TestAuthentication.authenticate(admin);
            String body = mockMvc.perform(get(adminPlayer(orgA, founder, lessonTask)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains(TASK_ANSWER).doesNotContain(ANSWER_CANARY);

            TestAuthentication.authenticate(coachGranted);
            String coachBody = mockMvc.perform(get(coachPlayer(founder, lessonTask)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(coachBody).contains(TASK_ANSWER).doesNotContain(ANSWER_CANARY);
        }
    }

    /* ------------------------------------------------------------- seeding */

    private String adminPlayer(Organization org, User member, UUID taskId) {
        return "/api/organizations/" + org.getId() + "/members/" + member.getId()
                + "/program-tasks/" + taskId + "/player";
    }

    private String coachPlayer(User founderUser, UUID taskId) {
        return "/api/v1/coach/founders/" + founderUser.getId() + "/program-tasks/" + taskId
                + "/player";
    }

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

    /** One module: a LESSON with one field (answered), a COURSE task, and a DRAFT. */
    private void seedBoard() {
        UUID moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, position, assign_mode, lock_mode)
                VALUES (?, ?, 'Module One', 0, 'ALL', 'UNLOCKED')
                """, moduleId, cohort);

        lessonTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type)
                VALUES (?, ?, 'Pricing Lesson', 'LIVE', 0, 'LESSON')
                """, lessonTask, moduleId);
        fieldId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_task_fields (id, task_id, field_type, required, position, config)
                VALUES (?, ?, 'LONG', TRUE, 0, ?::jsonb)
                """, fieldId, lessonTask, "{\"question\": \"How do you price?\"}");
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, answers, saved_at,
                                                 submitted_at)
                VALUES (?, ?, 'SUBMITTED', ?::jsonb, now(), now())
                """, lessonTask, founder.getId(),
                "{\"" + fieldId + "\": \"" + TASK_ANSWER + "\"}");

        courseTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type, ref_id)
                VALUES (?, ?, 'Course Task', 'LIVE', 1, 'COURSE', ?)
                """, courseTask, moduleId, UUID.randomUUID());

        draftTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type)
                VALUES (?, ?, 'Draft Lesson', 'DRAFT', 2, 'LESSON')
                """, draftTask, moduleId);
    }

    /** An evaluated assessment whose free-text answer must never leave the pipeline. */
    private void seedAssessmentAnswer() {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status) VALUES (?, 'Founder Mindset', 'PUBLISHED')",
                pipelineId);
        UUID pillarId = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name) VALUES (?, ?, 'Vision')",
                pillarId, pipelineId);
        UUID questionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO questions (id, pillar_id, type, prompt_text)
                VALUES (?, ?, 'FREE_TEXT', 'What nearly stopped you?')
                """, questionId, pillarId);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, orgA.getId(), founder.getId(), admin.getId());
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now(), now())
                """, submissionId, assignmentId, founder.getId());
        jdbc.update("INSERT INTO answers (submission_id, question_id, response_text) VALUES (?, ?, ?)",
                submissionId, questionId, ANSWER_CANARY);
    }
}
