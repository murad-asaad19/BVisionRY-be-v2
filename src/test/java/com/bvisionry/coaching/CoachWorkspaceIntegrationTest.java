package com.bvisionry.coaching;

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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase E (redesign spec §2.2) — the coach workspace reads, end to end against
 * the real schema:
 *
 * <ul>
 *   <li>roster triage numbers: FRI + Δ (earliest vs latest evaluated), current
 *       module (lowest incomplete, per-type done), the open-items split
 *       (awaiting review + the unread-replies proxy) and last activity;</li>
 *   <li>the review queue: oldest first, resubmitted marker, CoachAccess
 *       scoping (an ungranted founder's submission is simply not there);</li>
 *   <li>spine-aware module progress: an EXERCISE task counts when its owning
 *       slice says SUBMITTED/REVIEWED — no longer LESSON-only;</li>
 *   <li>the member announcements read: own cohorts only, §7b timestamps,
 *       author name + role.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CoachWorkspaceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.bvisionry.programflow.repository.TaskSpineRepository spine;
    @Autowired private com.bvisionry.exercise.ExerciseReviewService reviewService;
    @Autowired private com.bvisionry.exercise.ExerciseSubmissionService submissionService;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private Organization orgA;
    private Organization orgB;
    private User coach;      // COACH in org A: cohort1 grant + direct(f2, f3)
    private User f1;         // "a.founder" — cohort1: FRI 58→71, 1 review + 1 reply open
    private User f2;         // "b.founder" — direct grant: driven through the review loop
    private User f3;         // "c.founder" — direct grant: idle (login 9 days ago, nothing else)
    private User fHidden;    // cohort2, NO grant — never in roster or queue
    private User fB;         // another tenant
    private UUID cohort1;
    private UUID cohort2;
    private UUID assignmentF1;   // template T1 · SUBMITTED 10 days ago, never reviewed
    private UUID assignmentF2;   // template T1 · SUBMITTED 12 days ago; the loop test recycles it
    private UUID moduleOne;

    @BeforeEach
    void seed() {
        orgA = saveOrg("Workspace Org A");
        orgB = saveOrg("Workspace Org B");

        coach = saveUser("coach.a@test.invalid", UserRole.COACH, orgA);
        f1 = saveUser("a.founder@test.invalid", UserRole.MEMBER, orgA);
        f2 = saveUser("b.founder@test.invalid", UserRole.MEMBER, orgA);
        f3 = saveUser("c.founder@test.invalid", UserRole.MEMBER, orgA);
        fHidden = saveUser("hidden.founder@test.invalid", UserRole.MEMBER, orgA);
        fB = saveUser("foreign.founder@test.invalid", UserRole.MEMBER, orgB);

        cohort1 = insertCohort(orgA.getId(), "Cohort One", f1.getId());
        cohort2 = insertCohort(orgA.getId(), "Cohort Two", fHidden.getId());
        UUID cohortB = insertCohort(orgB.getId(), "Cohort B", fB.getId());

        insertGrant(orgA.getId(), coach.getId(), cohort1, null);
        insertGrant(orgA.getId(), coach.getId(), null, f2.getId());
        insertGrant(orgA.getId(), coach.getId(), null, f3.getId());

        // f3's whole activity is one login, 9 days ago — the idle founder.
        jdbc.update("UPDATE users SET last_login_at = now() - interval '9 days 1 hour' WHERE id = ?",
                f3.getId());

        seedProgram();
        seedFriTrajectory();
        seedExercises();
        seedAnnouncements(cohortB);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    /* -------------------------------------------------------------- program */

    /**
     * Cohort One: Module One (pos 0) = LESSON done + EXERCISE done (via the
     * exercise slice, NOT program_submissions) · Module Two (pos 1) = LESSON
     * not done → current module is "Module Two", completion is 2/3.
     */
    private void seedProgram() {
        moduleOne = insertModule(cohort1, "Module One", 0);
        UUID moduleTwo = insertModule(cohort1, "Module Two", 1);
        UUID lessonDone = insertTask(moduleOne, "Lesson One", "LESSON", null);
        insertTask(moduleOne, "Exercise Task", "EXERCISE", templateT1());
        insertTask(moduleTwo, "Lesson Two", "LESSON", null);
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, submitted_at)
                VALUES (?, ?, 'SUBMITTED', now())
                """, lessonDone, f1.getId());
    }

    /** Two evaluated submissions for f1: 58.00 (30 days ago) → 71.00 (yesterday). */
    private void seedFriTrajectory() {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status, created_by) VALUES (?, 'WS Pipeline', 'PUBLISHED', ?)",
                pipelineId, coach.getId());
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, orgA.getId(), f1.getId(), coach.getId());
        insertEvaluated(assignmentId, f1.getId(), "58.00", "30 days");
        insertEvaluated(assignmentId, f1.getId(), "71.00", "1 day");
    }

    private void insertEvaluated(UUID assignmentId, UUID userId, String score, String ago) {
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', now() - interval '%s', now() - interval '%s')
                """.formatted(ago, ago), submissionId, assignmentId, userId);
        jdbc.update("""
                INSERT INTO overall_summaries (submission_id, overall_score_percentage)
                VALUES (?, ?)
                """, submissionId, new java.math.BigDecimal(score));
    }

    private UUID templateT1;
    private UUID templateT2;

    private UUID templateT1() {
        if (templateT1 == null) {
            templateT1 = insertTemplate("Competitor Analysis");
        }
        return templateT1;
    }

    private void seedExercises() {
        templateT2 = insertTemplate("Pricing Sheet");

        // f1: SUBMITTED 10 days ago, never reviewed → queue row 2, awaiting_review 1.
        assignmentF1 = insertExercise(templateT1(), f1.getId(), "SUBMITTED",
                "now() - interval '10 days'");
        // f1 also holds a CHANGES_REQUESTED copy — back with the member, so it is
        // in NO queue and no awaiting count. Stamped the way requestChanges does.
        UUID changesRequested = insertExercise(templateT2, f1.getId(), "CHANGES_REQUESTED",
                "now() - interval '12 days'");
        jdbc.update("""
                UPDATE exercise_submissions
                SET changes_requested_at = now() - interval '11 days'
                WHERE assignment_id = ?
                """, changesRequested);
        // f2: SUBMITTED 12 days ago — the review loop test drives it through
        // request-changes → resubmit via the REAL services.
        assignmentF2 = insertExercise(templateT1(), f2.getId(), "SUBMITTED",
                "now() - interval '12 days'");
        // The ungranted founder's submission must never surface.
        insertExercise(templateT1(), fHidden.getId(), "SUBMITTED",
                "now() - interval '20 days'");

        // Comment threads on f1's submission (unread-replies proxy):
        UUID submissionF1 = jdbc.queryForObject(
                "SELECT id FROM exercise_submissions WHERE assignment_id = ?", UUID.class,
                assignmentF1);
        // A: coach root, founder replied last, OPEN → counts.
        UUID rootA = insertComment(submissionF1, coach.getId(), null, "OPEN", "3 days");
        insertComment(submissionF1, f1.getId(), rootA, "OPEN", "2 days");
        // B: coach root, no reply, OPEN → ball is with the founder, no count.
        insertComment(submissionF1, coach.getId(), null, "OPEN", "3 days");
        // C: founder replied last but the thread is RESOLVED → no count.
        UUID rootC = insertComment(submissionF1, coach.getId(), null, "RESOLVED", "4 days");
        insertComment(submissionF1, f1.getId(), rootC, "OPEN", "3 days");
    }

    private void seedAnnouncements(UUID cohortB) {
        insertAnnouncement(orgA.getId(), cohort1, coach.getId(),
                "Older post", "2 days");
        insertAnnouncement(orgA.getId(), cohort1, coach.getId(),
                "Demo day moves to Friday 10:00", "1 hour");
        insertAnnouncement(orgA.getId(), cohort2, coach.getId(),
                "Cohort Two only", "1 hour");
        insertAnnouncement(orgB.getId(), cohortB, null,
                "Foreign tenant post", "1 hour");
    }

    /* ------------------------------------------------------- roster triage */

    @Nested
    class RosterTriage {

        @Test
        void triageColumnsCarryFriDeltaModuleOpenItemsAndActivity() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/roster"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.founders", hasSize(3)))
                    // f1 — ordered first by name.
                    .andExpect(jsonPath("$.founders[0].name", is("a.founder")))
                    .andExpect(jsonPath("$.founders[0].friLatest", is(71.0)))
                    .andExpect(jsonPath("$.founders[0].friDelta", is(13.0)))
                    .andExpect(jsonPath("$.founders[0].currentModule", is("Module Two")))
                    .andExpect(jsonPath("$.founders[0].awaitingReview", is(1)))
                    .andExpect(jsonPath("$.founders[0].unreadReplies", is(1)))
                    // Spine-aware completion: LESSON + EXERCISE done, one lesson open.
                    .andExpect(jsonPath("$.founders[0].totalTasks", is(3)))
                    .andExpect(jsonPath("$.founders[0].submittedTasks", is(2)))
                    .andExpect(jsonPath("$.founders[0].lastActivityAt", notNullValue()))
                    .andExpect(jsonPath("$.founders[0].idleDays", is(0)))
                    // f2 — one evaluated point is no delta.
                    .andExpect(jsonPath("$.founders[1].name", is("b.founder")))
                    .andExpect(jsonPath("$.founders[1].friLatest", nullValue()))
                    .andExpect(jsonPath("$.founders[1].friDelta", nullValue()))
                    .andExpect(jsonPath("$.founders[1].awaitingReview", is(1)))
                    .andExpect(jsonPath("$.founders[1].unreadReplies", is(0)))
                    // f3 — the idle founder: only a login, 9 days ago.
                    .andExpect(jsonPath("$.founders[2].name", is("c.founder")))
                    .andExpect(jsonPath("$.founders[2].idleDays", is(9)))
                    .andExpect(jsonPath("$.founders[2].currentModule", nullValue()))
                    .andExpect(jsonPath("$.founders[2].awaitingReview", is(0)));
        }

        @Test
        void aCoachAnswerFlipsTheUnreadReplyOff() throws Exception {
            // The coach answers thread A last → the ball moves to the founder.
            UUID submissionF1 = jdbc.queryForObject(
                    "SELECT id FROM exercise_submissions WHERE assignment_id = ?", UUID.class,
                    assignmentF1);
            UUID rootA = jdbc.queryForObject("""
                    SELECT id FROM exercise_comments
                    WHERE submission_id = ? AND parent_id IS NULL AND status = 'OPEN'
                    ORDER BY created_at LIMIT 1
                    """, UUID.class, submissionF1);
            insertComment(submissionF1, coach.getId(), rootA, "OPEN", "1 hour");

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/roster"))
                    .andExpect(jsonPath("$.founders[0].unreadReplies", is(0)));
        }
    }

    /* -------------------------------------------------------- review queue */

    @Nested
    class ReviewQueue {

        /**
         * The resubmitted marker is driven through the REAL loop — coach
         * requests changes (service), member resubmits (service) — because
         * {@code reviewed_at} is nulled by both sides and can never carry it;
         * only {@code changes_requested_at} survives the round trip.
         */
        @Test
        void oldestFirstWithResubmittedMarker_viaTheRealReviewLoop() throws Exception {
            // Before any review: oldest first, nothing marked resubmitted;
            // fHidden's 20-day-old submission is NOT here — no grant, no row —
            // and f1's CHANGES_REQUESTED copy is with the member, not queued.
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/queue"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[0].assignmentId", is(assignmentF2.toString())))
                    .andExpect(jsonPath("$.items[0].resubmitted", is(false)))
                    .andExpect(jsonPath("$.items[1].assignmentId", is(assignmentF1.toString())))
                    .andExpect(jsonPath("$.items[1].founderName", is("a.founder")))
                    .andExpect(jsonPath("$.items[1].exerciseName", is("Competitor Analysis")))
                    .andExpect(jsonPath("$.items[1].resubmitted", is(false)))
                    .andExpect(jsonPath("$.items[1].submittedAt", notNullValue()));

            // Coach sends f2's copy back → it leaves the queue.
            reviewService.requestChanges(orgA.getId(), assignmentF2);
            entityManager.flush(); // the queue read is raw SQL on the same tx
            mockMvc.perform(get("/api/v1/coach/queue"))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].assignmentId", is(assignmentF1.toString())));

            // f2 resubmits through the member service → back in the queue,
            // NEWEST (submittedAt re-stamped), marked resubmitted, §7b stamp.
            UUID submissionF2 = jdbc.queryForObject(
                    "SELECT id FROM exercise_submissions WHERE assignment_id = ?", UUID.class,
                    assignmentF2);
            TestAuthentication.authenticate(f2);
            submissionService.submit(submissionF2, f2.getId());
            entityManager.flush();

            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/queue"))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[0].assignmentId", is(assignmentF1.toString())))
                    .andExpect(jsonPath("$.items[0].resubmitted", is(false)))
                    .andExpect(jsonPath("$.items[0].changesRequestedAt", nullValue()))
                    .andExpect(jsonPath("$.items[1].assignmentId", is(assignmentF2.toString())))
                    .andExpect(jsonPath("$.items[1].founderName", is("b.founder")))
                    .andExpect(jsonPath("$.items[1].resubmitted", is(true)))
                    .andExpect(jsonPath("$.items[1].changesRequestedAt", notNullValue()));
        }

        @Test
        void nonCoachRolesNeverReachTheQueue() throws Exception {
            TestAuthentication.authenticate(f1);
            mockMvc.perform(get("/api/v1/coach/queue")).andExpect(status().isForbidden());
        }
    }

    /* ---------------------------------------------- spine-aware progress */

    @Nested
    class SpineAwareModuleProgress {

        @Test
        void anExerciseTaskCountsThroughItsOwningSlice() throws Exception {
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + f1.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modules", hasSize(2)))
                    .andExpect(jsonPath("$.modules[0].moduleName", is("Module One")))
                    .andExpect(jsonPath("$.modules[0].totalTasks", is(2)))
                    // LESSON submitted + EXERCISE SUBMITTED in the exercise slice.
                    .andExpect(jsonPath("$.modules[0].submittedTasks", is(2)))
                    .andExpect(jsonPath("$.modules[1].moduleName", is("Module Two")))
                    .andExpect(jsonPath("$.modules[1].submittedTasks", is(0)));
        }

        /**
         * The due-reminder job's lifted "already done" filter (Phase E): the
         * same fragment answers per-type — f1 has DONE the exercise task via
         * the exercise slice, nobody has done the open lesson.
         */
        @Test
        void reminderDoneFilterFollowsThePerTypeRule() {
            UUID exerciseTask = jdbc.queryForObject(
                    "SELECT id FROM program_tasks WHERE name = 'Exercise Task'", UUID.class);
            UUID openLesson = jdbc.queryForObject(
                    "SELECT id FROM program_tasks WHERE name = 'Lesson Two'", UUID.class);
            org.assertj.core.api.Assertions.assertThat(spine.usersDoneWithTask(exerciseTask))
                    .containsExactly(f1.getId());
            org.assertj.core.api.Assertions.assertThat(spine.usersDoneWithTask(openLesson))
                    .isEmpty();
        }

        @Test
        void aChangesRequestedExerciseDoesNotCount() throws Exception {
            // Point the EXERCISE task at the template whose copy came back to
            // the member — the member's side is NOT done.
            jdbc.update("UPDATE program_tasks SET ref_id = ? WHERE name = 'Exercise Task'",
                    templateT2);
            TestAuthentication.authenticate(coach);
            mockMvc.perform(get("/api/v1/coach/founders/" + f1.getId()))
                    .andExpect(jsonPath("$.modules[0].submittedTasks", is(1)));
        }
    }

    /* ------------------------------------------------ member announcements */

    @Nested
    class MemberAnnouncements {

        @Test
        void aMemberReadsTheirOwnCohortsNewestFirstWithAuthorAndStamp() throws Exception {
            TestAuthentication.authenticate(f1);
            mockMvc.perform(get("/api/my/announcements"))
                    .andExpect(status().isOk())
                    // Cohort Two's and the foreign tenant's posts are absent.
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].body", is("Demo day moves to Friday 10:00")))
                    .andExpect(jsonPath("$[0].cohortName", is("Cohort One")))
                    .andExpect(jsonPath("$[0].authorName", is("coach.a")))
                    .andExpect(jsonPath("$[0].authorRole", is("COACH")))
                    .andExpect(jsonPath("$[0].createdAt", notNullValue()))
                    .andExpect(jsonPath("$[1].body", is("Older post")));
        }

        @Test
        void aMemberOutsideEveryCohortGetsAnEmptyList() throws Exception {
            TestAuthentication.authenticate(f2);
            mockMvc.perform(get("/api/my/announcements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void anOrgLessAccountGetsAnEmptyListNotAnError() throws Exception {
            TestAuthentication.authenticate(saveUser("orgless@test.invalid", UserRole.MEMBER, null));
            mockMvc.perform(get("/api/my/announcements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    /* ---------------------------------------------------------------- seed */

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

    private void insertGrant(UUID orgId, UUID coachId, UUID cohortId, UUID memberId) {
        jdbc.update("""
                INSERT INTO coach_assignments (org_id, coach_id, cohort_id, member_id)
                VALUES (?, ?, ?, ?)
                """, orgId, coachId, cohortId, memberId);
    }

    private UUID insertModule(UUID cohortId, String name, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, org_id, cohort_id, name, assign_mode, lock_mode, position)
                VALUES (?, ?, ?, ?, 'ALL', 'UNLOCKED', ?)
                """, id, orgA.getId(), cohortId, name, position);
        return id;
    }

    private UUID insertTask(UUID moduleId, String name, String type, UUID refId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, task_type, ref_id)
                VALUES (?, ?, ?, 'LIVE', ?, ?)
                """, id, moduleId, name, type, refId);
        return id;
    }

    private UUID insertTemplate(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_templates (id, name, status, created_by) VALUES (?, ?, 'PUBLISHED', ?)",
                id, name, coach.getId());
        return id;
    }

    private UUID insertExercise(UUID templateId, UUID userId, String status,
                                String submittedAtSql) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, templateId, orgA.getId(), userId, coach.getId());
        jdbc.update("""
                INSERT INTO exercise_submissions (assignment_id, user_id, status, submitted_at)
                VALUES (?, ?, ?, %s)
                """.formatted(submittedAtSql), assignmentId, userId, status);
        // One live row so a resubmit through the real service passes its
        // "add at least one row" validation.
        jdbc.update("INSERT INTO exercise_rows (submission_id) SELECT id FROM exercise_submissions WHERE assignment_id = ?",
                assignmentId);
        return assignmentId;
    }

    private UUID insertComment(UUID submissionId, UUID authorId, UUID parentId,
                               String status, String ago) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_comments (id, submission_id, author_id, parent_id, body, status, created_at)
                VALUES (?, ?, ?, ?, 'body', ?, now() - interval '%s')
                """.formatted(ago), id, submissionId, authorId, parentId, status);
        return id;
    }

    private void insertAnnouncement(UUID orgId, UUID cohortId, UUID authorId,
                                    String body, String ago) {
        jdbc.update("""
                INSERT INTO announcements (org_id, cohort_id, author_id, body, created_at)
                VALUES (?, ?, ?, ?, now() - interval '%s')
                """.formatted(ago), orgId, cohortId, authorId, body);
    }
}
