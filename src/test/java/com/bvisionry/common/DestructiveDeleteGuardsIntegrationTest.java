package com.bvisionry.common;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.web.AuthoringService;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.dto.PipelineStatusRequest;
import com.bvisionry.pipeline.service.PipelineService;
import com.bvisionry.programflow.web.CohortService;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import com.bvisionry.workshops.web.WorkshopAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting (or reverting) must never silently destroy member work — the
 * standing rule set with exercise templates and extended to every slice.
 * Each case seeds the minimum "one member worked here" row and pins:
 * refusal with work, success without, and — the pipeline headline — that
 * ARCHIVED now PRESERVES submissions instead of wiping them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class DestructiveDeleteGuardsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PipelineService pipelineService;
    @Autowired private CohortService cohortService;
    @Autowired private WorkshopAdminService workshopAdminService;
    @Autowired private AuthoringService authoringService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization org;
    private User admin;
    private User member;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Guard Org");
        org.setActive(true);
        org = organizationRepository.saveAndFlush(org);

        admin = saveUser("guard.admin@test.invalid", UserRole.SUPER_ADMIN);
        member = saveUser("guard.member@test.invalid", UserRole.MEMBER);
        TestAuthentication.authenticate(admin);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* ------------------------------------------------------------ pipeline */

    @Test
    void archivingAPipelineKeepsEveryAssignmentAndSubmission() {
        UUID pipelineId = insertPipeline("Keep My Work", "PUBLISHED");
        UUID assignmentId = insertAssignment(pipelineId);
        insertSubmission(assignmentId);

        pipelineService.transitionStatus(pipelineId,
                new PipelineStatusRequest(PipelineStatus.ARCHIVED));

        assertThat(count("assignments", "pipeline_id", pipelineId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM submissions WHERE assignment_id = ?", Long.class, assignmentId))
                .isEqualTo(1);
    }

    @Test
    void anArchivedPipelineCanBeRepublishedAndResumes() {
        UUID pipelineId = insertPipeline("Oops Archived", "PUBLISHED");
        UUID assignmentId = insertAssignment(pipelineId);
        insertSubmission(assignmentId);
        // A pipeline must have a pillar with a question to pass publish readiness.
        UUID pillarId = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name) VALUES (?, ?, 'Grit')",
                pillarId, pipelineId);
        jdbc.update("""
                INSERT INTO questions (id, pillar_id, type, prompt_text, display_order)
                VALUES (?, ?, 'FREE_TEXT', 'How gritty?', 0)
                """, UUID.randomUUID(), pillarId);

        pipelineService.transitionStatus(pipelineId,
                new PipelineStatusRequest(PipelineStatus.ARCHIVED));
        // The un-archive escape hatch: everything survived, so republish resumes.
        var republished = pipelineService.transitionStatus(pipelineId,
                new PipelineStatusRequest(PipelineStatus.PUBLISHED));

        assertThat(republished.status()).isEqualTo(PipelineStatus.PUBLISHED);
        assertThat(count("assignments", "pipeline_id", pipelineId)).isEqualTo(1);
    }

    @Test
    void revertingToDraftIsRefusedOnceAMemberSubmitted() {
        UUID pipelineId = insertPipeline("No Silent Wipe", "PUBLISHED");
        insertSubmission(insertAssignment(pipelineId));

        assertThatThrownBy(() -> pipelineService.transitionStatus(pipelineId,
                new PipelineStatusRequest(PipelineStatus.DRAFT)))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Create a new version instead");
    }

    @Test
    void revertingToDraftIsRefusedWhileAPublicLinkExists() {
        UUID pipelineId = insertPipeline("Public QR", "PUBLISHED");
        jdbc.update("""
                INSERT INTO public_assessment_links (token, pipeline_id, title, created_by)
                VALUES (?, ?, 'QR', ?)
                """, UUID.randomUUID(), pipelineId, admin.getId());

        assertThatThrownBy(() -> pipelineService.transitionStatus(pipelineId,
                new PipelineStatusRequest(PipelineStatus.DRAFT)))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("public link");
    }

    @Test
    void pipelineDeleteRefusedWhenAssignedAndAllowedWhenNot() {
        UUID assigned = insertPipeline("Assigned", "PUBLISHED");
        insertAssignment(assigned);
        UUID unassigned = insertPipeline("Shelf", "DRAFT");

        assertThatThrownBy(() -> pipelineService.deletePipeline(assigned))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Archive it instead");
        assertThatCode(() -> pipelineService.deletePipeline(unassigned))
                .doesNotThrowAnyException();
    }

    @Test
    void pipelineListDeletableAgreesWithTheDeleteGuard() {
        UUID assigned = insertPipeline("Assigned", "PUBLISHED");
        insertAssignment(assigned);
        UUID unassigned = insertPipeline("Shelf", "DRAFT");

        var byId = pipelineService.listAll(null).stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.id(), p -> p.deletable()));
        assertThat(byId.get(assigned)).isFalse();
        assertThat(byId.get(unassigned)).isTrue();
    }

    /* -------------------------------------------------------------- cohort */

    @Test
    void cohortDeleteHardRefusedOnceAMemberHasWork() {
        UUID cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Guard Cohort', 'LAUNCHED')",
                cohortId);
        UUID moduleId = UUID.randomUUID();
        jdbc.update("INSERT INTO program_modules (id, cohort_id, name, position) VALUES (?, ?, 'Week 01', 0)",
                moduleId, cohortId);
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, task_type)
                VALUES (?, ?, 'Reflection', 'LIVE', 'LESSON')
                """, taskId, moduleId);
        jdbc.update("INSERT INTO program_submissions (id, task_id, user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), taskId, member.getId());

        assertThatThrownBy(() -> cohortService.delete(cohortId))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("work from 1 member");

        UUID emptyCohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Empty Cohort', 'DRAFT')",
                emptyCohortId);
        assertThatCode(() -> cohortService.delete(emptyCohortId)).doesNotThrowAnyException();
    }

    @Test
    void cohortDeleteRefusedWhenOnlySessionAttendanceExists() {
        UUID cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Attended Cohort', 'LAUNCHED')",
                cohortId);
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sessions (id, cohort_id, type, session_date)
                VALUES (?, ?, 'WORKSHOP', now())
                """, sessionId, cohortId);
        jdbc.update("INSERT INTO session_attendance (session_id, member_id) VALUES (?, ?)",
                sessionId, member.getId());

        assertThatThrownBy(() -> cohortService.delete(cohortId))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("work from 1 member");
    }

    /* ------------------------------------------------------------ workshop */

    @Test
    void workshopDeleteRefusedWhileSubmissionsExist() {
        UUID workshopId = UUID.randomUUID();
        jdbc.update("INSERT INTO workshops (id, org_id, name) VALUES (?, ?, 'Guard Workshop')",
                workshopId, org.getId());
        UUID exerciseId = UUID.randomUUID();
        jdbc.update("INSERT INTO workshop_exercises (id, workshop_id, title) VALUES (?, ?, 'Ex 1')",
                exerciseId, workshopId);
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workshop_exercise_tasks (id, exercise_id, task_type, title)
                VALUES (?, ?, 'QUESTION', 'Task 1')
                """, taskId, exerciseId);
        UUID teamId = UUID.randomUUID();
        jdbc.update("INSERT INTO workshop_teams (id, workshop_id, name) VALUES (?, ?, 'Team A')",
                teamId, workshopId);
        jdbc.update("""
                INSERT INTO workshop_task_submissions (id, task_id, team_id, user_id)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), taskId, teamId, member.getId());

        assertThatThrownBy(() -> workshopAdminService.delete(org.getId(), workshopId))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Reset it first");

        // Reset is the one deliberate wipe — afterwards the delete goes through.
        workshopAdminService.reset(org.getId(), workshopId);
        assertThatCode(() -> workshopAdminService.delete(org.getId(), workshopId))
                .doesNotThrowAnyException();
    }

    @Test
    void workshopDeleteRefusedWhileOnlyIntroSurveyResponsesExist() {
        UUID workshopId = UUID.randomUUID();
        jdbc.update("INSERT INTO workshops (id, org_id, name) VALUES (?, ?, 'Survey Guard Workshop')",
                workshopId, org.getId());
        UUID surveyId = UUID.randomUUID();
        jdbc.update("INSERT INTO surveys (id, name, created_by) VALUES (?, 'Pre-workshop pulse', ?)",
                surveyId, admin.getId());
        // A pre-survey response is member work with NO task submission behind
        // it — and it survives even member deletion (respondent is SET NULL).
        jdbc.update("""
                INSERT INTO survey_responses (id, survey_id, source, workshop_id, respondent_user_id)
                VALUES (?, ?, 'WORKSHOP_INTRO', ?, ?)
                """, UUID.randomUUID(), surveyId, workshopId, member.getId());

        assertThatThrownBy(() -> workshopAdminService.delete(org.getId(), workshopId))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Reset it first");
    }

    /* ------------------------------------------------- DB-level backstop */

    /**
     * V212's whole point: even when the app guard is raced (work committed
     * between count and delete), the RESTRICT FK makes the database refuse
     * the parent delete instead of cascading the fresh work away. One
     * representative edge pins the migration actually flipped.
     */
    @Test
    void restrictFkRefusesACourseDeleteThatBypassesTheGuard() {
        UUID courseId = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title) VALUES (?, ?, 'raced-course', 'Raced Course')",
                courseId, org.getId());
        jdbc.update("INSERT INTO enrollment (id, user_id, course_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), member.getId(), courseId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM course WHERE id = ?", courseId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /* -------------------------------------------------------------- course */

    @Test
    void courseDeleteRefusedOnceALearnerEnrolled() {
        UUID courseId = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title) VALUES (?, ?, 'guard-course', 'Guard Course')",
                courseId, org.getId());
        jdbc.update("INSERT INTO enrollment (id, user_id, course_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), member.getId(), courseId);

        assertThatThrownBy(() -> authoringService.deleteCourse("guard-course"))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Archive it instead");

        jdbc.update("INSERT INTO course (id, org_id, slug, title) VALUES (?, ?, 'empty-course', 'Empty Course')",
                UUID.randomUUID(), org.getId());
        assertThatCode(() -> authoringService.deleteCourse("empty-course"))
                .doesNotThrowAnyException();
    }

    /* ------------------------------------------------------------- helpers */

    private UUID insertPipeline(String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status, created_by) VALUES (?, ?, ?, ?)",
                id, name, status, admin.getId());
        return id;
    }

    private UUID insertAssignment(UUID pipelineId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, assigned_by, user_id)
                VALUES (?, ?, ?, ?, ?)
                """, id, pipelineId, org.getId(), admin.getId(), member.getId());
        return id;
    }

    private void insertSubmission(UUID assignmentId) {
        jdbc.update("INSERT INTO submissions (id, assignment_id, user_id, status) VALUES (?, ?, ?, 'SUBMITTED')",
                UUID.randomUUID(), assignmentId, member.getId());
    }

    private long count(String table, String column, UUID value) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
        return n == null ? 0 : n;
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
}
