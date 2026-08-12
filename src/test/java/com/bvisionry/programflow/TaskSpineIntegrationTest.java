package com.bvisionry.programflow;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.FieldValidationException;
import com.bvisionry.engagement.repository.EngagementReadRepository;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.ProgramTaskType;
import com.bvisionry.programflow.dto.DirectAssignmentDto;
import com.bvisionry.programflow.dto.JourneyResponse;
import com.bvisionry.programflow.dto.JourneyResponse.JourneyTask;
import com.bvisionry.programflow.dto.JourneyTaskState;
import com.bvisionry.programflow.dto.OpenTaskResponse;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.PulseResponse;
import com.bvisionry.programflow.dto.PulseResponse.CellState;
import com.bvisionry.programflow.dto.SaveBoardRequest.TaskUpsert;
import com.bvisionry.programflow.web.MyProgramService;
import com.bvisionry.programflow.web.ProgramAdminService;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.BoardPayloads;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The typed task spine end to end against the real schema (redesign spec §1,
 * §2.1, §5): per-type journey statuses, the idempotent open endpoint with
 * milestone tagging, milestone uniqueness + pipeline-sync validation,
 * direct-assignment exclusion, pulse/progress with mixed types, and the
 * engagement assignments formula over typed tasks.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class TaskSpineIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MyProgramService myProgramService;
    @Autowired private ProgramAdminService programAdminService;
    @Autowired private EngagementReadRepository engagementReads;
    @Autowired private com.bvisionry.programflow.repository.TaskSpineRepository spineRepo;

    private Organization org;
    private User member;
    private UUID cohortId;
    private UUID moduleId;
    private UUID pipelineId;
    private UUID courseId;
    private UUID coveredTemplateId;
    private UUID surveyId;

    private UUID lessonTaskId;
    private UUID courseTaskId;
    private UUID exerciseTaskId;
    private UUID baselineTaskId;
    private UUID checkinTaskId;
    private UUID surveyTaskId;

    @BeforeEach
    void seed() {
        org = saveOrg("Spine Org");
        member = saveUser("spine.member@test.invalid", UserRole.MEMBER, org);

        pipelineId = insertPipeline("Founder Mindset");
        courseId = insertCourse("Pricing Foundations");
        coveredTemplateId = insertTemplate("Competitor Analysis");
        surveyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO surveys (id, name, status, created_by)
                VALUES (?, ?, 'PUBLISHED', ?)
                """, surveyId, "Week 2 reflection", member.getId());

        cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, ?, 'LAUNCHED')",
                cohortId, "Cohort 1");
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                cohortId, org.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohortId, member.getId());
        moduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, lock_mode)
                VALUES (?, ?, ?, 'UNLOCKED')
                """, moduleId, cohortId, "Know Your Market");

        lessonTaskId = insertTask("Lesson", "LESSON", null, null, 0);
        courseTaskId = insertTask("Course task", "COURSE", courseId, null, 1);
        exerciseTaskId = insertTask("Exercise task", "EXERCISE", coveredTemplateId, null, 2);
        baselineTaskId = insertTask("Baseline check-in", "ASSESSMENT", pipelineId, "BASELINE", 3);
        checkinTaskId = insertTask("Mid check-in", "ASSESSMENT", pipelineId, "CHECKIN", 4);
        surveyTaskId = insertTask("Survey task", "SURVEY", surveyId, null, 5);

        TestAuthentication.authenticate(member);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* ------------------------------------------------------ journey statuses */

    @Nested
    class JourneyStatuses {

        @Test
        void freshMember_everyTypedTaskIsNotStarted() {
            JourneyResponse journey = myProgramService.journey(cohortId);
            List<JourneyTask> rows = journey.modules().get(0).tasks();
            assertThat(rows).hasSize(6);
            assertThat(rows).allSatisfy(t ->
                    assertThat(t.state()).isEqualTo(JourneyTaskState.NOT_STARTED));
            assertThat(rows.get(3).milestoneRole()).isEqualTo(MilestoneRole.BASELINE);
            assertThat(rows.get(4).milestoneRole()).isEqualTo(MilestoneRole.CHECKIN);
            // 6: every typed task counts since D2 shipped the member survey
            // route (completableInApp() is true for all types now).
            assertThat(journey.progress().total()).isEqualTo(6);
            assertThat(journey.progress().done()).isZero();
            // §11: the journey hero shows cohort size.
            assertThat(journey.memberCount()).isEqualTo(1);
        }

        @Test
        void statesComeFromTheOwningSlices_withMilestonePayoff() {
            // COURSE in progress at 40%.
            jdbc.update("""
                    INSERT INTO enrollment (user_id, course_id, status, progress_pct)
                    VALUES (?, ?, 'ACTIVE', 40)
                    """, member.getId(), courseId);
            // EXERCISE submitted — the copy this cohort task owns (V173 tag).
            UUID ea = insertExerciseAssignment(coveredTemplateId, member.getId(), exerciseTaskId);
            jdbc.update("""
                    INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at)
                    VALUES (?, ?, 'SUBMITTED', now())
                    """, ea, member.getId());
            // ASSESSMENT milestones: baseline 58 then check-in 64, SAME pipeline,
            // distinguishable purely by their program_task_id tags.
            insertTaggedEvaluatedSubmission(baselineTaskId, 40, new BigDecimal("58.00"));
            insertTaggedEvaluatedSubmission(checkinTaskId, 5, new BigDecimal("64.00"));
            // SURVEY participation, tagged to this cohort's task.
            insertSurveyResponse(surveyId, member.getId(), surveyTaskId);

            JourneyResponse journey = myProgramService.journey(cohortId);
            List<JourneyTask> rows = journey.modules().get(0).tasks();

            assertThat(rows.get(0).state()).isEqualTo(JourneyTaskState.NOT_STARTED);
            assertThat(rows.get(1).state()).isEqualTo(JourneyTaskState.IN_PROGRESS);
            assertThat(rows.get(1).progressPct()).isEqualTo(40);
            assertThat(rows.get(2).state()).isEqualTo(JourneyTaskState.SUBMITTED);
            assertThat(rows.get(2).submittedAt()).isNotNull();

            JourneyTask baseline = rows.get(3);
            assertThat(baseline.state()).isEqualTo(JourneyTaskState.EVALUATED);
            assertThat(baseline.score()).isEqualByComparingTo("58.00");
            assertThat(baseline.scoreBefore()).isNull();
            JourneyTask checkin = rows.get(4);
            assertThat(checkin.state()).isEqualTo(JourneyTaskState.EVALUATED);
            assertThat(checkin.score()).isEqualByComparingTo("64.00");
            // The payoff band: previous evaluated milestone → this one (58 → 64).
            assertThat(checkin.scoreBefore()).isEqualByComparingTo("58.00");

            assertThat(rows.get(5).state()).isEqualTo(JourneyTaskState.DONE);

            // Progress counts every typed done-state; the lesson and the
            // in-progress course don't count. The answered SURVEY counts on
            // both sides since D2 (member survey route shipped).
            assertThat(journey.progress().done()).isEqualTo(4);
            assertThat(journey.progress().total()).isEqualTo(6);
            // Gamification points stay LESSON-only this phase.
            assertThat(journey.gamification().points()).isZero();

            // Engagement assignments formula agrees with the spine (6 counted
            // cohort tasks — survey included since D2 — 4 done; the member's
            // exercise assignment is TAGGED to the cohort task, so it adds
            // nothing).
            var counts = engagementReads.assignmentCounts(cohortId, member.getId());
            assertThat(counts.total()).isEqualTo(6);
            assertThat(counts.done()).isEqualTo(4);
        }
    }

    /* ------------------------------------------------------------------ open */

    @Nested
    class OpenEndpoint {

        @Test
        void courseOpen_enrollsOnceAndIsIdempotent() {
            OpenTaskResponse first = myProgramService.open(courseTaskId);
            OpenTaskResponse second = myProgramService.open(courseTaskId);
            assertThat(first.targetId()).isEqualTo(courseId);
            assertThat(second.targetId()).isEqualTo(courseId);
            Integer enrollments = jdbc.queryForObject(
                    "SELECT count(*) FROM enrollment WHERE user_id = ? AND course_id = ?",
                    Integer.class, member.getId(), courseId);
            assertThat(enrollments).isEqualTo(1);
        }

        @Test
        void exerciseOpen_createsAssignmentPlusWorkingCopyWithStarterRows_once() {
            OpenTaskResponse first = myProgramService.open(exerciseTaskId);
            OpenTaskResponse second = myProgramService.open(exerciseTaskId);
            assertThat(first.targetId()).isNotNull().isEqualTo(second.targetId());
            Integer assignments = jdbc.queryForObject("""
                    SELECT count(*) FROM exercise_assignments
                    WHERE organization_id = ? AND template_id = ? AND user_id = ?
                    """, Integer.class, org.getId(), coveredTemplateId, member.getId());
            assertThat(assignments).isEqualTo(1);
            Integer starterRows = jdbc.queryForObject(
                    "SELECT count(*) FROM exercise_rows WHERE submission_id = ? AND is_starter",
                    Integer.class, first.targetId());
            assertThat(starterRows).isEqualTo(1);
        }

        @Test
        void assessmentOpen_createsTheTaggedSubmission_once() {
            OpenTaskResponse first = myProgramService.open(baselineTaskId);
            OpenTaskResponse second = myProgramService.open(baselineTaskId);
            assertThat(first.targetId()).isNotNull().isEqualTo(second.targetId());
            UUID tag = jdbc.queryForObject(
                    "SELECT program_task_id FROM submissions WHERE id = ?",
                    UUID.class, first.targetId());
            assertThat(tag).isEqualTo(baselineTaskId);
            Integer assignments = jdbc.queryForObject("""
                    SELECT count(*) FROM assignments
                    WHERE organization_id = ? AND pipeline_id = ? AND user_id = ?
                    """, Integer.class, org.getId(), pipelineId, member.getId());
            assertThat(assignments).isEqualTo(1);

            // A second milestone on the SAME pipeline reuses the assignment but
            // spawns its own tagged submission.
            OpenTaskResponse checkin = myProgramService.open(checkinTaskId);
            assertThat(checkin.targetId()).isNotEqualTo(first.targetId());
            assertThat(jdbc.queryForObject(
                    "SELECT program_task_id FROM submissions WHERE id = ?",
                    UUID.class, checkin.targetId())).isEqualTo(checkinTaskId);
        }

        @Test
        void lessonAndSurveyOpen_justPointAtTheirTargets() {
            assertThat(myProgramService.open(lessonTaskId).targetId()).isEqualTo(lessonTaskId);
            assertThat(myProgramService.open(surveyTaskId).targetId()).isEqualTo(surveyId);
        }

        /**
         * The double-open race (review #1): both callers miss the find and
         * both insert — uq_submissions_user_program_task turns the loser into
         * a no-op that re-selects the winner's row.
         */
        @Test
        void doubleOpenRace_resolvesToOneTaggedSubmission() {
            UUID first = spineRepo.createTaggedSubmission(
                    org.getId(), pipelineId, member.getId(), baselineTaskId);
            UUID second = spineRepo.createTaggedSubmission(
                    org.getId(), pipelineId, member.getId(), baselineTaskId);
            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM submissions WHERE user_id = ? AND program_task_id = ?",
                    Integer.class, member.getId(), baselineTaskId)).isEqualTo(1);
        }

        /**
         * Review #2: an admin-CANCELLED enrollment reads as not started, and
         * open() hands the course back with progress intact (mirrors
         * EnrollmentService.reactivateIfRemoved).
         */
        @Test
        void cancelledEnrollment_readsNotStarted_andOpenReactivatesWithProgress() {
            jdbc.update("""
                    INSERT INTO enrollment (user_id, course_id, status, progress_pct)
                    VALUES (?, ?, 'CANCELLED', 60)
                    """, member.getId(), courseId);

            JourneyTask courseRow = myProgramService.journey(cohortId)
                    .modules().get(0).tasks().get(1);
            assertThat(courseRow.state()).isEqualTo(JourneyTaskState.NOT_STARTED);

            myProgramService.open(courseTaskId);

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM enrollment WHERE user_id = ? AND course_id = ?",
                    String.class, member.getId(), courseId)).isEqualTo("ACTIVE");
            courseRow = myProgramService.journey(cohortId).modules().get(0).tasks().get(1);
            assertThat(courseRow.state()).isEqualTo(JourneyTaskState.IN_PROGRESS);
            assertThat(courseRow.progressPct()).isEqualTo(60);
        }
    }

    /* ------------------------------------------------------- survey gating */

    @Nested
    class SurveyGatesDrip {

        /**
         * D2 flip: SURVEY is completable in-app now (member survey route), so
         * an unanswered survey-only module 1 sequential-locks module 2 like
         * any other task — and a response unlocks it and counts in progress.
         */
        @Test
        void surveyOnlyModule_gatesTheNextModule_untilAnswered() {
            UUID dripCohort = UUID.randomUUID();
            jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, ?, 'LAUNCHED')",
                    dripCohort, "Drip cohort");
            jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                    dripCohort, org.getId());
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    dripCohort, member.getId());
            UUID m1 = UUID.randomUUID();
            UUID m2 = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_modules (id, cohort_id, name, position, lock_mode)
                    VALUES (?, ?, 'Module 1', 0, 'SEQUENTIAL')
                    """, m1, dripCohort);
            jdbc.update("""
                    INSERT INTO program_modules (id, cohort_id, name, position, lock_mode)
                    VALUES (?, ?, 'Module 2', 1, 'SEQUENTIAL')
                    """, m2, dripCohort);
            UUID dripSurveyTask = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_tasks (id, module_id, name, status, task_type, ref_id)
                    VALUES (?, ?, 'Blockerless survey', 'LIVE', 'SURVEY', ?)
                    """, dripSurveyTask, m1, surveyId);
            UUID lesson = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_tasks (id, module_id, name, status, task_type)
                    VALUES (?, ?, 'Lesson', 'LIVE', 'LESSON')
                    """, lesson, m2);
            jdbc.update("""
                    INSERT INTO program_submissions (task_id, user_id, status, answers, submitted_at)
                    VALUES (?, ?, 'SUBMITTED', '{}'::jsonb, now())
                    """, lesson, member.getId());

            // Unanswered survey → module 2 stays sequential-locked; the
            // already-submitted lesson still counts (progress ≠ lock).
            JourneyResponse locked = myProgramService.journey(dripCohort);
            assertThat(locked.modules().get(1).lockState())
                    .isEqualTo(JourneyResponse.LockState.LOCKED_SEQUENTIAL);
            assertThat(locked.progress().done()).isEqualTo(1);
            assertThat(locked.progress().total()).isEqualTo(2);

            // A survey response (the D2 member route's row shape) unlocks it.
            insertSurveyResponse(surveyId, member.getId(), dripSurveyTask);
            JourneyResponse unlocked = myProgramService.journey(dripCohort);
            assertThat(unlocked.modules().get(1).lockState())
                    .isEqualTo(JourneyResponse.LockState.UNLOCKED);
            assertThat(unlocked.progress().done()).isEqualTo(2);
            assertThat(unlocked.progress().total()).isEqualTo(2);
        }
    }

    /* ------------------------------------------------------------ validation */

    /**
     * The typed-spine rules as the builder now meets them: one whole-board Save
     * (§13.10), rejected with the offending task NAMED rather than a per-field
     * 400 — on a forty-card board "pick what this references" is unactionable.
     */
    @Nested
    class MilestoneValidation {

        @Test
        void aCohortGetsAtMostOneBaselineAndOneDistance() {
            // Turning the CHECKIN into the (first) DISTANCE is fine…
            saveTask(checkinTaskId, assessment("Distance", pipelineId, MilestoneRole.DISTANCE));
            // …but a second DISTANCE must be rejected. (Which of the two the
            // message names is board order, not edit order — the payload has no
            // notion of "the one just retyped"; ProgramBoardSaveIntegrationTest
            // pins the naming.)
            assertThatThrownBy(() -> saveTask(baselineTaskId,
                    assessment("Another distance", pipelineId, MilestoneRole.DISTANCE)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already has a distance milestone");
            // Same for a second BASELINE.
            assertThatThrownBy(() -> saveTask(checkinTaskId,
                    assessment("Second baseline", pipelineId, MilestoneRole.BASELINE)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already has a baseline milestone");
        }

        @Test
        void nonLessonTasksRejectFormFields_andLiveNeedsARef() {
            assertThatThrownBy(() -> saveTask(courseTaskId,
                    t -> new TaskUpsert(t.id(), "Course task", null, ProgramTaskStatus.LIVE,
                            false, ProgramTaskType.COURSE, courseId, null,
                            List.of(new com.bvisionry.programflow.dto.FieldUpsert(null,
                                    com.bvisionry.programflow.domain.FieldType.SHORT, false,
                                    Map.of())))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Course task")
                    .hasMessageContaining("form fields");
            assertThatThrownBy(() -> saveTask(courseTaskId,
                    t -> new TaskUpsert(t.id(), "Course task", null, ProgramTaskStatus.LIVE,
                            false, ProgramTaskType.COURSE, null, null, List.of())))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Course task")
                    .hasMessageContaining("before publishing it");
        }

        @Test
        void samePipelinePairDesignationIsNowAllowed_butMustMatchMilestoneTasks() {
            // The pre-spine equal-pair rejection is gone: same instrument in
            // and out is the FRI 58 → 71 story.
            ProgramSettingsDto saved = programAdminService.updateSettings(cohortId,
                    settings(pipelineId, pipelineId));
            assertThat(saved.baselinePipelineId()).isEqualTo(pipelineId);
            assertThat(saved.distancePipelineId()).isEqualTo(pipelineId);

            // A designation that contradicts the cohort's milestone tasks is a
            // field error (the DISTANCE milestone below references pipelineId).
            saveTask(checkinTaskId, assessment("Distance", pipelineId, MilestoneRole.DISTANCE));
            UUID otherPipeline = insertPipeline("Other instrument");
            assertThatThrownBy(() -> programAdminService.updateSettings(cohortId,
                    settings(pipelineId, otherPipeline)))
                    .isInstanceOfSatisfying(FieldValidationException.class,
                            e -> assertThat(e.getFieldErrors()).containsKey("distancePipelineId"));

            // And the mirror: once designated, a milestone task cannot point
            // at a different pipeline.
            assertThatThrownBy(() -> saveTask(checkinTaskId,
                    assessment("Distance", otherPipeline, MilestoneRole.DISTANCE)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("designated distance assessment is a different pipeline");
        }

        /** Review #7a: the ref must exist in its owning slice. */
        @Test
        void aBogusReference_isRejected() {
            assertThatThrownBy(() -> saveTask(courseTaskId,
                    t -> new TaskUpsert(t.id(), "Course task", null, ProgramTaskStatus.LIVE,
                            false, ProgramTaskType.COURSE, UUID.randomUUID(), null, List.of())))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not found");
        }

        /** Review #7b: once a milestone has tagged submissions, its ref is frozen. */
        @Test
        void milestoneRefFreezes_onceMembersHaveAnswered() {
            myProgramService.open(baselineTaskId); // creates the tagged submission
            UUID otherPipeline = insertPipeline("Replacement instrument");
            assertThatThrownBy(() -> saveTask(baselineTaskId,
                    assessment("Baseline", otherPipeline, MilestoneRole.BASELINE)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("no longer change");
        }

        /** The builder's one write, carrying a single retyped task. */
        private void saveTask(UUID taskId, java.util.function.UnaryOperator<TaskUpsert> edit) {
            programAdminService.saveBoard(cohortId,
                    BoardPayloads.edit(programAdminService.getBoard(cohortId), taskId, edit));
        }

        private java.util.function.UnaryOperator<TaskUpsert> assessment(String name, UUID refId,
                MilestoneRole role) {
            return t -> new TaskUpsert(t.id(), name, null, ProgramTaskStatus.LIVE, false,
                    ProgramTaskType.ASSESSMENT, refId, role, List.of());
        }

        private ProgramSettingsDto settings(UUID baseline, UUID distance) {
            return new ProgramSettingsDto("Week", true, 3, null, null, baseline, distance);
        }
    }

    /* ---------------------------------------------------- direct assignments */

    @Nested
    class DirectAssignments {

        @Test
        void cohortCoveredWorkIsExcluded_trulyDirectWorkAppears() {
            // Covered: exercise assignment TAGGED to the cohort task (V173),
            // enrollment in the cohort task's course, and an assessment
            // assignment REPRESENTED by a milestone tag (opening the baseline
            // task creates the assignment + tagged submission).
            UUID coveredEa = insertExerciseAssignment(
                    coveredTemplateId, member.getId(), exerciseTaskId);
            jdbc.update("""
                    INSERT INTO exercise_submissions (assignment_id, user_id)
                    VALUES (?, ?)
                    """, coveredEa, member.getId());
            jdbc.update("INSERT INTO enrollment (user_id, course_id) VALUES (?, ?)",
                    member.getId(), courseId);
            myProgramService.open(baselineTaskId);

            // Truly direct: a second template, course and pipeline no cohort
            // task references.
            UUID directTemplate = insertTemplate("Session Log");
            UUID directEa = insertExerciseAssignment(directTemplate, member.getId());
            jdbc.update("""
                    INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at)
                    VALUES (?, ?, 'SUBMITTED', now())
                    """, directEa, member.getId());
            UUID directCourse = insertCourse("Storytelling 101");
            jdbc.update("INSERT INTO enrollment (user_id, course_id) VALUES (?, ?)",
                    member.getId(), directCourse);
            UUID directPipeline = insertPipeline("Public funnel assessment");
            jdbc.update("""
                    INSERT INTO assignments (pipeline_id, organization_id, user_id, assigned_by)
                    VALUES (?, ?, ?, ?)
                    """, directPipeline, org.getId(), member.getId(), member.getId());

            List<DirectAssignmentDto> direct = myProgramService.journey(cohortId).directAssignments();

            assertThat(direct).extracting(DirectAssignmentDto::refId)
                    .containsExactlyInAnyOrder(directTemplate, directCourse, directPipeline)
                    .doesNotContain(coveredTemplateId, courseId, pipelineId);
            DirectAssignmentDto exercise = direct.stream()
                    .filter(d -> d.taskType() == ProgramTaskType.EXERCISE).findFirst().orElseThrow();
            assertThat(exercise.state()).isEqualTo(JourneyTaskState.SUBMITTED);
            assertThat(exercise.assignedAt()).isNotNull();
            assertThat(exercise.submittedAt()).isNotNull();
            DirectAssignmentDto assessment = direct.stream()
                    .filter(d -> d.taskType() == ProgramTaskType.ASSESSMENT).findFirst().orElseThrow();
            assertThat(assessment.state()).isEqualTo(JourneyTaskState.NOT_STARTED);
        }

        /**
         * Review decision #6: exclusion is representation-based. An untagged,
         * never-evaluated assignment on the baseline pipeline is not (yet)
         * represented by any cohort task, so it stays in the direct list.
         */
        @Test
        void unrepresentedAssignmentOnACoveredPipeline_staysListed() {
            jdbc.update("""
                    INSERT INTO assignments (pipeline_id, organization_id, user_id, assigned_by)
                    VALUES (?, ?, ?, ?)
                    """, pipelineId, org.getId(), member.getId(), member.getId());

            List<DirectAssignmentDto> direct = myProgramService.journey(cohortId).directAssignments();
            assertThat(direct).extracting(DirectAssignmentDto::refId).containsExactly(pipelineId);
        }

        /**
         * Review decision #6: a pre-spine evaluated intake surfaces on the
         * BASELINE milestone band (earliest-evaluated fallback, same rule as
         * the comparison) and leaves the direct list; CHECKIN stays tag-only.
         */
        @Test
        void preSpineEvaluatedIntake_showsOnTheBaselineBand_andLeavesTheDirectList() {
            insertTaggedEvaluatedSubmission(null, 30, new BigDecimal("52.00"));

            List<JourneyTask> rows = myProgramService.journey(cohortId)
                    .modules().get(0).tasks();
            JourneyTask baseline = rows.get(3);
            assertThat(baseline.state()).isEqualTo(JourneyTaskState.EVALUATED);
            assertThat(baseline.score()).isEqualByComparingTo("52.00");
            assertThat(rows.get(4).state()).isEqualTo(JourneyTaskState.NOT_STARTED);

            assertThat(myProgramService.journey(cohortId).directAssignments()).isEmpty();
        }

        @Test
        void memberWithoutAnyCohort_stillGetsDirectAssignments() {
            User loner = saveUser("spine.loner@test.invalid", UserRole.MEMBER, org);
            UUID template = insertTemplate("Solo exercise");
            UUID ea = insertExerciseAssignment(template, loner.getId());
            jdbc.update("INSERT INTO exercise_submissions (assignment_id, user_id) VALUES (?, ?)",
                    ea, loner.getId());

            TestAuthentication.authenticate(loner);
            JourneyResponse journey = myProgramService.journey(null);
            assertThat(journey.cohortId()).isNull();
            assertThat(journey.modules()).isEmpty();
            assertThat(journey.directAssignments())
                    .extracting(DirectAssignmentDto::refId).containsExactly(template);
        }
    }

    /* ------------------------------------------ shared refs, two cohorts */

    /**
     * Spec §13: a member can sit in several cohorts whose tasks point at the
     * SAME pipeline, exercise template or survey. The tag on the owning slice's
     * row decides which cohort the work belongs to — cohort A's progress must
     * never bleed onto cohort B's untouched tasks.
     */
    @Nested
    class SharedRefsAcrossCohorts {

        private UUID otherCohortId;
        private UUID otherExerciseTaskId;
        private UUID otherSurveyTaskId;

        @Autowired private com.bvisionry.survey.service.ProgramTaskSurveyService surveyResponses;
        @Autowired private jakarta.persistence.EntityManager em;

        @BeforeEach
        void secondCohortSharingEveryRef() {
            otherCohortId = UUID.randomUUID();
            jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, ?, 'LAUNCHED')",
                    otherCohortId, "Alpha Founders");
            jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                    otherCohortId, org.getId());
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    otherCohortId, member.getId());
            UUID otherModuleId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_modules (id, cohort_id, name, position, lock_mode)
                    VALUES (?, ?, 'Intake', 0, 'UNLOCKED')
                    """, otherModuleId, otherCohortId);
            jdbc.update("""
                    INSERT INTO program_tasks (id, module_id, name, status, position,
                                               task_type, ref_id, milestone_role)
                    VALUES (?, ?, 'Baseline', 'LIVE', 0, 'ASSESSMENT', ?, 'BASELINE')
                    """, UUID.randomUUID(), otherModuleId, pipelineId);
            otherExerciseTaskId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_tasks (id, module_id, name, status, position,
                                               task_type, ref_id)
                    VALUES (?, ?, 'Exercise task', 'LIVE', 1, 'EXERCISE', ?)
                    """, otherExerciseTaskId, otherModuleId, coveredTemplateId);
            otherSurveyTaskId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO program_tasks (id, module_id, name, status, position,
                                               task_type, ref_id)
                    VALUES (?, ?, 'Survey task', 'LIVE', 2, 'SURVEY', ?)
                    """, otherSurveyTaskId, otherModuleId, surveyId);
        }

        /**
         * The reported bug: a member completed the exercise and the survey in
         * "QA Full Cycle" and "Alpha Founders" immediately showed both as done,
         * because state was keyed on (member, template) / (member, survey). It
         * is keyed on the task tag now (V173), so each cohort owns its own copy.
         */
        @Test
        void exerciseAndSurveyDoneInOneCohort_leaveTheOtherCohortUntouched() {
            // Do the work in cohort 1, through the same routes a member uses.
            UUID submissionId = myProgramService.open(exerciseTaskId).targetId();
            jdbc.update("""
                    UPDATE exercise_submissions SET status = 'SUBMITTED', submitted_at = now()
                    WHERE id = ?
                    """, submissionId);
            surveyResponses.submitForProgramTask(surveyTaskId, member.getId(),
                    new com.bvisionry.survey.dto.SurveySubmitRequest(null, null, List.of()),
                    "it-agent");
            em.flush();

            List<JourneyTask> done = myProgramService.journey(cohortId).modules().get(0).tasks();
            assertThat(done.get(2).state()).isEqualTo(JourneyTaskState.SUBMITTED);
            assertThat(done.get(5).state()).isEqualTo(JourneyTaskState.DONE);

            List<JourneyTask> untouched =
                    myProgramService.journey(otherCohortId).modules().get(0).tasks();
            assertThat(untouched.get(1).state()).isEqualTo(JourneyTaskState.NOT_STARTED);
            assertThat(untouched.get(2).state()).isEqualTo(JourneyTaskState.NOT_STARTED);

            // …and the admin surface agrees (the founders matrix reads the
            // same spine).
            PulseResponse pulse = programAdminService.getPulse(otherCohortId, null);
            PulseResponse.PulseRow row = pulse.rows().stream()
                    .filter(r -> r.userId().equals(member.getId())).findFirst().orElseThrow();
            assertThat(row.cells().get(1)).isEqualTo(CellState.NOT_STARTED);
            assertThat(row.cells().get(2)).isEqualTo(CellState.NOT_STARTED);

            // The other cohort's task is still takeable: its own assignment and
            // its own survey answer, neither blocked by the first cohort's.
            assertThat(myProgramService.open(otherExerciseTaskId).targetId())
                    .isNotEqualTo(submissionId);
            assertThat(surveyResponses.getForProgramTask(otherSurveyTaskId, member.getId()).id())
                    .isEqualTo(surveyId);
        }

        /** The bug: cohort A's tagged baseline showed up on cohort B's band. */
        @Test
        void aTaggedBaselineStaysInTheCohortItWasTakenIn() {
            insertTaggedEvaluatedSubmission(baselineTaskId, 1, new BigDecimal("68.00"));

            JourneyTask taken = myProgramService.journey(cohortId)
                    .modules().get(0).tasks().get(3);
            assertThat(taken.state()).isEqualTo(JourneyTaskState.EVALUATED);
            assertThat(taken.score()).isEqualByComparingTo("68.00");

            JourneyTask untouched = myProgramService.journey(otherCohortId)
                    .modules().get(0).tasks().get(0);
            assertThat(untouched.state()).isEqualTo(JourneyTaskState.NOT_STARTED);
            assertThat(untouched.score()).isNull();
        }

        /**
         * …and the case the fallback exists for still works: a submission
         * tagged to NO cohort task belongs to the member, not to a cohort, so
         * every untouched baseline on that pipeline adopts it.
         */
        @Test
        void anUntaggedEvaluatedSubmission_isStillAdoptedByEveryUntouchedBaseline() {
            insertTaggedEvaluatedSubmission(null, 30, new BigDecimal("52.00"));

            List<JourneyTask> baselines = List.of(
                    myProgramService.journey(cohortId).modules().get(0).tasks().get(3),
                    myProgramService.journey(otherCohortId).modules().get(0).tasks().get(0));
            assertThat(baselines).allSatisfy(b -> {
                assertThat(b.state()).isEqualTo(JourneyTaskState.EVALUATED);
                assertThat(b.score()).isEqualByComparingTo("52.00");
            });
        }
    }

    /* -------------------------------------------------- member survey route */

    @Nested
    class MemberSurveyRoute {

        @Autowired private com.bvisionry.survey.service.ProgramTaskSurveyService surveyResponses;
        @Autowired private jakarta.persistence.EntityManager em;

        @Test
        void memberTakesTheSurveyTaskInApp_andTheJourneyFlipsToDone() {
            // GET hands back the published definition for the enrolled member.
            var dto = surveyResponses.getForProgramTask(surveyTaskId, member.getId());
            assertThat(dto.id()).isEqualTo(surveyId);

            // POST creates the PROGRAM_TASK response…
            var result = surveyResponses.submitForProgramTask(surveyTaskId, member.getId(),
                    new com.bvisionry.survey.dto.SurveySubmitRequest(null, null, List.of()),
                    "it-agent");
            assertThat(result.responseId()).isNotNull();
            // The journey + duplicate gates read via JdbcTemplate — flush the
            // JPA insert so they see the row inside this test transaction.
            em.flush();
            String source = jdbc.queryForObject(
                    "SELECT source FROM survey_responses WHERE id = ?",
                    String.class, result.responseId());
            assertThat(source).isEqualTo("PROGRAM_TASK");

            // …which IS the completion: journey DONE and counted (6/6 sides).
            JourneyResponse journey = myProgramService.journey(cohortId);
            JourneyTask surveyRow = journey.modules().get(0).tasks().get(5);
            assertThat(surveyRow.state()).isEqualTo(JourneyTaskState.DONE);
            assertThat(surveyRow.completedAt()).isNotNull();
            assertThat(journey.progress().done()).isEqualTo(1);
            assertThat(journey.progress().total()).isEqualTo(6);

            // Second take → 409-shaped duplicate, on GET and POST alike.
            assertThatThrownBy(() ->
                    surveyResponses.getForProgramTask(surveyTaskId, member.getId()))
                    .isInstanceOf(com.bvisionry.common.exception.DuplicateResourceException.class);
            assertThatThrownBy(() -> surveyResponses.submitForProgramTask(surveyTaskId,
                    member.getId(),
                    new com.bvisionry.survey.dto.SurveySubmitRequest(null, null, List.of()),
                    "it-agent"))
                    .isInstanceOf(com.bvisionry.common.exception.DuplicateResourceException.class);
        }

        @Test
        void aStrangerToTheCohort_getsA404_neverTheDefinition() {
            User stranger = saveUser("spine.stranger@test.invalid", UserRole.MEMBER, org);
            assertThatThrownBy(() ->
                    surveyResponses.getForProgramTask(surveyTaskId, stranger.getId()))
                    .isInstanceOf(com.bvisionry.common.exception.ResourceNotFoundException.class);
        }
    }

    /* ----------------------------------------------------------------- pulse */

    @Nested
    class PulseMixedTypes {

        @Test
        void typedCellsReadTheirOwningSlice() {
            jdbc.update("""
                    INSERT INTO enrollment (user_id, course_id, status, progress_pct, completed_at)
                    VALUES (?, ?, 'COMPLETED', 100, now())
                    """, member.getId(), courseId);
            UUID ea = insertExerciseAssignment(coveredTemplateId, member.getId(), exerciseTaskId);
            jdbc.update("INSERT INTO exercise_submissions (assignment_id, user_id) VALUES (?, ?)",
                    ea, member.getId());
            insertTaggedEvaluatedSubmission(baselineTaskId, 1, new BigDecimal("58.00"));

            PulseResponse pulse = programAdminService.getPulse(cohortId, null);
            assertThat(pulse.columns()).hasSize(6);
            PulseResponse.PulseRow row = pulse.rows().stream()
                    .filter(r -> r.userId().equals(member.getId())).findFirst().orElseThrow();
            // Column order mirrors board order (see seed()).
            assertThat(row.cells().get(0)).isEqualTo(CellState.NOT_STARTED);  // lesson
            assertThat(row.cells().get(1)).isEqualTo(CellState.SUBMITTED);    // completed course
            assertThat(row.cells().get(2)).isEqualTo(CellState.IN_DRAFT);     // in-progress exercise
            assertThat(row.cells().get(3)).isEqualTo(CellState.SUBMITTED);    // evaluated baseline
            assertThat(row.cells().get(4)).isEqualTo(CellState.NOT_STARTED);  // untouched check-in
            assertThat(row.cells().get(5)).isEqualTo(CellState.NOT_STARTED);  // survey
            // Denominator is 6: the survey counts since D2 (member route shipped).
            assertThat(row.completionPct()).isEqualTo(Math.round(2 * 100f / 6));
        }
    }

    /* ----------------------------------------------------------------- seeding */

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

    private UUID insertPipeline(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status, created_by) VALUES (?, ?, 'PUBLISHED', ?)",
                id, name, member.getId());
        return id;
    }

    /**
     * PUBLISHED because the cohort tasks below are LIVE, and the spine forbids a
     * live COURSE task pointing at an unpublished course — a whole-board save
     * validates every card, so the seed has to be a board that is legal to save.
     */
    private UUID insertCourse(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title, state) "
                        + "VALUES (?, ?, ?, ?, 'PUBLISHED')",
                id, org.getId(), title.toLowerCase().replace(' ', '-') + "-" + id, title);
        return id;
    }

    private UUID insertTemplate(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by, starter_rows)
                VALUES (?, ?, 'PUBLISHED', ?, '[{"c1": "seed"}]'::jsonb)
                """, id, name, member.getId());
        return id;
    }

    private UUID insert(String sql, Object... argsAfterId) {
        UUID id = UUID.randomUUID();
        Object[] args = new Object[argsAfterId.length + 1];
        args[0] = id;
        System.arraycopy(argsAfterId, 0, args, 1, argsAfterId.length);
        jdbc.update(sql, args);
        return id;
    }

    private UUID insertTask(String name, String type, UUID refId, String milestoneRole, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position,
                                           task_type, ref_id, milestone_role)
                VALUES (?, ?, ?, 'LIVE', ?, ?, ?, ?)
                """, id, moduleId, name, position, type, refId, milestoneRole);
        return id;
    }

    /** A DIRECT (untagged) exercise assignment — nobody's cohort task owns it. */
    private UUID insertExerciseAssignment(UUID templateId, UUID userId) {
        return insertExerciseAssignment(templateId, userId, null);
    }

    /** An assignment tagged to a cohort EXERCISE task, as {@code open()} writes it (V173). */
    private UUID insertExerciseAssignment(UUID templateId, UUID userId, UUID taskId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id,
                                                  assigned_by, program_task_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, templateId, org.getId(), userId, userId, taskId);
        return id;
    }

    /** A PROGRAM_TASK survey response tagged to a cohort SURVEY task (V173). */
    private void insertSurveyResponse(UUID surveyRef, UUID userId, UUID taskId) {
        jdbc.update("""
                INSERT INTO survey_responses (survey_id, source, respondent_user_id, program_task_id)
                VALUES (?, 'PROGRAM_TASK', ?, ?)
                """, surveyRef, userId, taskId);
    }

    /** An EVALUATED submission tagged to a milestone task, with its overall score. */
    private void insertTaggedEvaluatedSubmission(UUID taskId, int daysAgo, BigDecimal overall) {
        UUID assignmentId = jdbc
                .queryForList("SELECT id FROM assignments WHERE user_id = ? AND pipeline_id = ?",
                        UUID.class, member.getId(), pipelineId)
                .stream().findFirst().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                            VALUES (?, ?, ?, ?, ?)
                            """, id, pipelineId, org.getId(), member.getId(), member.getId());
                    return id;
                });
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, program_task_id,
                                         submitted_at, evaluated_at, created_at)
                VALUES (?, ?, ?, 'EVALUATED', ?,
                        now() - make_interval(days => ?), now() - make_interval(days => ?),
                        now() - make_interval(days => ?))
                """, submissionId, assignmentId, member.getId(), taskId, daysAgo, daysAgo, daysAgo);
        jdbc.update("INSERT INTO overall_summaries (submission_id, overall_score_percentage) VALUES (?, ?)",
                submissionId, overall);
    }
}
