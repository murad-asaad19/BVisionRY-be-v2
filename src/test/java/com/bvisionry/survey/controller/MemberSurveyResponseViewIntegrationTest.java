package com.bvisionry.survey.controller;

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
 * The staff SURVEY-response viewer ({@code MemberSurveyResponseViewController} →
 * {@code SurveyResultsService.responseDetailForProgramTask}, spec §2.4): the
 * founder profile's Work tab opening a member's answers to a cohort SURVEY task.
 *
 * <p>The read is keyed on {@code (program_task_id, respondent_user_id)} — the
 * V173 pair that makes a survey's completion cohort-scoped — so a survey the
 * member answered through a PUBLIC LINK or a post-assessment prompt is NOT
 * reachable here, which is the point: this door shows cohort work, not every
 * survey a person ever filled in.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class MemberSurveyResponseViewIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization orgA;
    private Organization orgB;
    private User admin;         // ORG_ADMIN org A
    private User adminB;        // ORG_ADMIN org B
    private User coachGranted;  // COACH org A, whole-cohort grant
    private User coachOutside;  // COACH org A, no grant
    private User founder;       // MEMBER org A, answered the survey task
    private User silent;        // MEMBER org A, same cohort, never answered
    private User founderB;      // MEMBER org B, same (shared) cohort, answered
    private UUID cohort;
    private UUID surveyTask;
    private UUID lessonTask;    // a non-SURVEY task on the same module
    private UUID responseId;
    private UUID responseBId;
    private UUID questionId;
    private UUID pillarId;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Survey View Org A");
        orgB = saveOrg("Survey View Org B");
        admin = saveUser("admin.a@test.invalid", UserRole.ORG_ADMIN, orgA);
        adminB = saveUser("admin.b@test.invalid", UserRole.ORG_ADMIN, orgB);
        coachGranted = saveUser("coach.granted@test.invalid", UserRole.COACH, orgA);
        coachOutside = saveUser("coach.outside@test.invalid", UserRole.COACH, orgA);
        founder = saveUser("founder.a@test.invalid", UserRole.MEMBER, orgA);
        silent = saveUser("founder.silent@test.invalid", UserRole.MEMBER, orgA);
        founderB = saveUser("founder.b@test.invalid", UserRole.MEMBER, orgB);

        cohort = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Pulse Cohort', 'LAUNCHED')",
                cohort);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohort,
                orgA.getId());
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)", cohort,
                orgB.getId());
        for (User member : new User[] {founder, silent, founderB}) {
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)", cohort,
                    member.getId());
        }
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, NULL)
                """, orgA.getId(), coachGranted.getId(), cohort);

        seedSurveyAndBoard();
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Nested
    class BothDoors {

        @Test
        void theMembersOwnSurveyAnswersComeBackOrderedByPillarThenQuestion()
                throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminResponse(orgA, founder, surveyTask)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.responseId", is(responseId.toString())))
                    .andExpect(jsonPath("$.source", is("PROGRAM_TASK")))
                    .andExpect(jsonPath("$.submittedAt", notNullValue()))
                    .andExpect(jsonPath("$.answers", hasSize(1)))
                    .andExpect(jsonPath("$.answers[0].questionId", is(questionId.toString())))
                    .andExpect(jsonPath("$.answers[0].pillarId", is(pillarId.toString())))
                    .andExpect(jsonPath("$.answers[0].pillarName", is("Momentum")))
                    .andExpect(jsonPath("$.answers[0].promptText",
                            is("What slowed you down this month?")))
                    .andExpect(jsonPath("$.answers[0].type", is("SHORT_TEXT")))
                    .andExpect(jsonPath("$.answers[0].responseText",
                            is("Hiring took longer than planned.")));
        }

        @Test
        void theGrantedCoachGetsTheIdenticalPayload() throws Exception {
            TestAuthentication.authenticate(admin);
            String adminBody = mockMvc.perform(get(adminResponse(orgA, founder, surveyTask)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            TestAuthentication.authenticate(coachGranted);
            String coachBody = mockMvc.perform(get(coachResponse(founder, surveyTask)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(coachBody).isEqualTo(adminBody);
        }

        /**
         * The read is the (task, member) pair and nothing else, so "this member
         * has not answered yet" is a 404 rather than an empty shell — and so is
         * a task that is not a SURVEY at all: nothing is ever tagged to it, so
         * there is no response to find. Unlike the LESSON player there is no
         * type guard and therefore no 400 surface here.
         */
        @Test
        void noResponseRowIsA404_onBothDoorsAndForANonSurveyTask() throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminResponse(orgA, silent, surveyTask)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(adminResponse(orgA, founder, lessonTask)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(adminResponse(orgA, founder, UUID.randomUUID())))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(coachGranted);
            mockMvc.perform(get(coachResponse(silent, surveyTask)))
                    .andExpect(status().isNotFound());
        }

        /**
         * A response the member gave OUTSIDE the cohort (a public link, say)
         * carries no {@code program_task_id}, so it is unreachable through this
         * door however the task id is guessed — the V173 tag is the whole
         * boundary between cohort work and a person's other survey history.
         */
        @Test
        void anUntaggedResponseIsNotReachableThroughTheTaskDoor() throws Exception {
            UUID publicResponse = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO survey_responses (id, survey_id, source, respondent_user_id,
                                                  submitted_at)
                    VALUES (?, (SELECT ref_id FROM program_tasks WHERE id = ?),
                            'PUBLIC_LINK', ?, now())
                    """, publicResponse, surveyTask, silent.getId());

            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminResponse(orgA, silent, surveyTask)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Gates {

        @Test
        void aForeignOrgAdminNeverReachesTheService() throws Exception {
            TestAuthentication.authenticate(adminB);
            mockMvc.perform(get(adminResponse(orgA, founder, surveyTask)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aCoachOutsideTheUnionIs404_whileAGrantedCoachIs200() throws Exception {
            TestAuthentication.authenticate(coachOutside);
            mockMvc.perform(get(coachResponse(founder, surveyTask)))
                    .andExpect(status().isNotFound());

            TestAuthentication.authenticate(coachGranted);
            mockMvc.perform(get(coachResponse(founder, surveyTask)))
                    .andExpect(status().isOk());
        }

        @Test
        void aMemberHasNoRouteThroughEitherStaffDoor() throws Exception {
            TestAuthentication.authenticate(founder);
            mockMvc.perform(get(adminResponse(orgA, founder, surveyTask)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(coachResponse(founder, surveyTask)))
                    .andExpect(status().isForbidden());
        }

        /**
         * Tenancy re-anchor, the twin of
         * {@code MemberTaskViewIntegrationTest}'s: the service's javadoc relies
         * on "CALLERS AUTHORIZE FIRST", and the class guard authorizes only the
         * CALLER. On a cohort shared between orgs (V171) that left another
         * tenant's founder's survey answers readable through org A's own path
         * until {@code OrgMemberAccess.requireMemberOf} re-anchored the member.
         */
        @Test
        void anotherTenantsMemberOnAsharedCohortIsNotReadableThroughEitherDoor()
                throws Exception {
            TestAuthentication.authenticate(admin);
            mockMvc.perform(get(adminResponse(orgA, founderB, surveyTask)))
                    .andExpect(status().isNotFound());

            // The coach door refuses it too — CoachAccess pins the founder to
            // the coach's own org.
            TestAuthentication.authenticate(coachGranted);
            mockMvc.perform(get(coachResponse(founderB, surveyTask)))
                    .andExpect(status().isNotFound());
    }
    }

    /* ------------------------------------------------------------- seeding */

    private String adminResponse(Organization org, User member, UUID taskId) {
        return "/api/organizations/" + org.getId() + "/members/" + member.getId()
                + "/program-tasks/" + taskId + "/survey-response";
    }

    private String coachResponse(User founderUser, UUID taskId) {
        return "/api/v1/coach/founders/" + founderUser.getId() + "/program-tasks/" + taskId
                + "/survey-response";
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

    /** One survey (one pillar, one question), a SURVEY task over it, and two tagged responses. */
    private void seedSurveyAndBoard() {
        UUID surveyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO surveys (id, name, status, created_by)
                VALUES (?, 'Monthly Pulse', 'PUBLISHED', ?)
                """, surveyId, admin.getId());
        pillarId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO survey_pillars (id, survey_id, name, display_order)
                VALUES (?, ?, 'Momentum', 0)
                """, pillarId, surveyId);
        questionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO survey_questions (id, pillar_id, type, prompt_text, display_order)
                VALUES (?, ?, 'SHORT_TEXT', 'What slowed you down this month?', 0)
                """, questionId, pillarId);

        UUID moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, position, assign_mode, lock_mode)
                VALUES (?, ?, 'Module One', 0, 'ALL', 'UNLOCKED')
                """, moduleId, cohort);
        surveyTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type, ref_id)
                VALUES (?, ?, 'Pulse Task', 'LIVE', 0, 'SURVEY', ?)
                """, surveyTask, moduleId, surveyId);
        lessonTask = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position, task_type)
                VALUES (?, ?, 'Reflection', 'LIVE', 1, 'LESSON')
                """, lessonTask, moduleId);

        responseId = insertResponse(surveyId, founder, "Hiring took longer than planned.");
        responseBId = insertResponse(surveyId, founderB, "Org B's private answer.");
    }

    private UUID insertResponse(UUID surveyId, User member, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO survey_responses (id, survey_id, source, respondent_user_id,
                                              program_task_id, submitted_at)
                VALUES (?, ?, 'PROGRAM_TASK', ?, ?, now())
                """, id, surveyId, member.getId(), surveyTask);
        jdbc.update("""
                INSERT INTO survey_answers (response_id, question_id, response_text)
                VALUES (?, ?, ?)
                """, id, questionId, text);
        return id;
    }
}
