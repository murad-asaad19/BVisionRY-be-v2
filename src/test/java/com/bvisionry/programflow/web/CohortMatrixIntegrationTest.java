package com.bvisionry.programflow.web;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.dto.CohortMatrixResponse;
import com.bvisionry.programflow.dto.CohortMatrixResponse.AttentionFlag;
import com.bvisionry.programflow.dto.CohortMatrixResponse.FounderRow;
import com.bvisionry.programflow.dto.JourneyTaskState;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The founders progress matrix happy path (spec §2.3): module columns with
 * pillar chips, milestone columns, per-founder cells, row-end triage and every
 * needs-attention flag reachable.
 *
 * <p>Cast: A logged in a month ago and has done nothing since (IDLE +
 * OVERDUE) · B did later-module work but skipped the baseline check-in
 * (CHECKIN_UNSTARTED) · C answered the baseline, scored 62 overall with one
 * pillar at 20 (< the default 40 threshold → PILLAR_BELOW_THRESHOLD) — and
 * then walked past the MID-PROGRAM check-in into module 3, so the generalized
 * passed-it-by rule flags C too · D was enrolled minutes ago and has no
 * footprint at all (no IDLE — null last-activity is "no data yet").
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CohortMatrixIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private ProgramAdminService adminService;
    @Autowired private OrganizationRepository orgs;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private Organization org;
    private User founderA;
    private User founderB;
    private User founderC;
    private User founderD;
    private UUID cohortId;
    private UUID lessonModule1TaskId;
    private UUID baselineTaskId;
    private UUID checkinTaskId;
    private UUID lessonModule2TaskId;
    private UUID lessonModule3TaskId;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Matrix Org");
        org.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        org.setActive(true);
        org = orgs.saveAndFlush(org);
        founderA = saveMember("a@matrix.invalid");
        founderB = saveMember("b@matrix.invalid");
        founderC = saveMember("c@matrix.invalid");
        founderD = saveMember("d@matrix.invalid");
        TestAuthentication.authenticateAsOrgAdmin(users, org);
        // A signed in once, a month ago, and never came back — genuinely idle.
        jdbc.update("UPDATE users SET last_login_at = now() - interval '30 days' WHERE id = ?",
                founderA.getId());

        UUID pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name, status, created_by) VALUES (?, ?, 'PUBLISHED', ?)",
                pipelineId, "Founder Readiness", founderA.getId());
        UUID pillarId = UUID.randomUUID();
        jdbc.update("INSERT INTO pillars (id, pipeline_id, name, display_order) VALUES (?, ?, ?, 0)",
                pillarId, pipelineId, "Mindset");

        cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status, launched_at) "
                + "VALUES (?, 'Matrix Cohort', 'LAUNCHED', now())", cohortId);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                cohortId, org.getId());
        for (User u : new User[] {founderA, founderB, founderC, founderD}) {
            jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                    cohortId, u.getId());
        }

        UUID module1 = insertModule("Foundation", "Mindset", 0);
        UUID module2 = insertModule("Deep Dive", null, 1);
        UUID module3 = insertModule("Ship It", null, 2);
        lessonModule1TaskId = insertTask(module1, "Intro lesson", "LESSON", null, null, 0, true);
        baselineTaskId = insertTask(module1, "Baseline check-in", "ASSESSMENT", pipelineId,
                "BASELINE", 1, false);
        lessonModule2TaskId = insertTask(module2, "Later lesson", "LESSON", null, null, 0, false);
        checkinTaskId = insertTask(module2, "Mid check-in", "ASSESSMENT", pipelineId,
                "CHECKIN", 1, false);
        lessonModule3TaskId = insertTask(module3, "Final lesson", "LESSON", null, null, 0, false);

        // B: did module-2 work, never the baseline.
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, answers, submitted_at)
                VALUES (?, ?, 'SUBMITTED', '{}'::jsonb, now())
                """, lessonModule2TaskId, founderB.getId());
        // C: did module-3 work, skipping the module-2 mid-program check-in.
        jdbc.update("""
                INSERT INTO program_submissions (task_id, user_id, status, answers, submitted_at)
                VALUES (?, ?, 'SUBMITTED', '{}'::jsonb, now())
                """, lessonModule3TaskId, founderC.getId());

        // C: evaluated baseline TAGGED to the milestone task, overall 62, pillar 20.
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, assignmentId, pipelineId, org.getId(), founderC.getId(), founderC.getId());
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, assignment_id, user_id, status, program_task_id,
                                         submitted_at, evaluated_at)
                VALUES (?, ?, ?, 'EVALUATED', ?, now(), now())
                """, submissionId, assignmentId, founderC.getId(), baselineTaskId);
        jdbc.update("INSERT INTO overall_summaries (submission_id, overall_score_percentage) VALUES (?, ?)",
                submissionId, new BigDecimal("62.00"));
        jdbc.update("""
                INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label)
                VALUES (?, ?, ?, 'Emerging')
                """, submissionId, pillarId, new BigDecimal("20.00"));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matrix_columnsCellsTriageAndAttentionFlags() {
        CohortMatrixResponse matrix = adminService.getMatrix(cohortId, null);

        // Columns: three modules (pillar chip on the first), two milestone
        // columns in board order (baseline, then the mid-program check-in).
        assertThat(matrix.moduleColumns()).hasSize(3);
        assertThat(matrix.moduleColumns().get(0).pillarLabel()).isEqualTo("Mindset");
        assertThat(matrix.milestoneColumns()).hasSize(2);
        assertThat(matrix.milestoneColumns().get(0).role()).isEqualTo(MilestoneRole.BASELINE);
        assertThat(matrix.milestoneColumns().get(0).taskId()).isEqualTo(baselineTaskId);
        assertThat(matrix.milestoneColumns().get(1).role()).isEqualTo(MilestoneRole.CHECKIN);
        assertThat(matrix.milestoneColumns().get(1).taskId()).isEqualTo(checkinTaskId);
        assertThat(matrix.pillarThreshold()).isEqualTo(40);

        assertThat(matrix.rows()).hasSize(4);
        FounderRow a = row(matrix, founderA);
        FounderRow b = row(matrix, founderB);
        FounderRow c = row(matrix, founderC);

        // A: untouched cohort → idle, overdue on the past-due lesson; no baseline noise.
        // The module cell counts the LESSON only — the baseline assessment has
        // its own milestone column and is excluded here, exactly as the member's
        // journey module chip excludes it (one "module task count" definition).
        assertThat(a.moduleCells().get(0).total()).isEqualTo(1);
        assertThat(a.moduleCells().get(0).done()).isZero();
        assertThat(a.milestoneCells().get(0).state()).isEqualTo(JourneyTaskState.NOT_STARTED);
        assertThat(a.attentionFlags())
                .contains(AttentionFlag.IDLE, AttentionFlag.OVERDUE_TASKS)
                .doesNotContain(AttentionFlag.CHECKIN_UNSTARTED, AttentionFlag.PILLAR_BELOW_THRESHOLD);
        assertThat(a.friLatest()).isNull();

        // B: later-module work while the baseline sits untouched → check-in unstarted, not idle.
        assertThat(b.moduleCells().get(1).done()).isEqualTo(1);
        assertThat(b.moduleCells().get(1).total()).as("mid check-in has its own column").isEqualTo(1);
        assertThat(b.attentionFlags())
                .contains(AttentionFlag.CHECKIN_UNSTARTED)
                .doesNotContain(AttentionFlag.IDLE);
        assertThat(b.lastSeenAt()).isNotNull();

        // C: baseline evaluated with score; pillar 20 < 40 → below threshold.
        // The BASELINE being done doesn't clear them: they walked past the
        // MID-PROGRAM check-in into module 3, so the generalized rule flags C.
        assertThat(c.milestoneCells().get(0).state()).isEqualTo(JourneyTaskState.EVALUATED);
        assertThat(c.milestoneCells().get(0).score()).isEqualByComparingTo("62.00");
        assertThat(c.milestoneCells().get(0).evaluatedAt()).isNotNull();
        assertThat(c.milestoneCells().get(1).state()).isEqualTo(JourneyTaskState.NOT_STARTED);
        assertThat(c.friLatest()).isEqualByComparingTo("62.00");
        assertThat(c.friDelta()).as("one data point has no delta").isNull();
        assertThat(c.attentionFlags())
                .contains(AttentionFlag.PILLAR_BELOW_THRESHOLD, AttentionFlag.CHECKIN_UNSTARTED);
        assertThat(c.awaitingReview()).isZero();
    }

    /**
     * A founder enrolled minutes ago has NO last activity — which is "no data
     * yet", not "idle for over a week". They still show up on the overdue
     * strip like anybody else; only the idle verdict is withheld.
     */
    @Test
    void brandNewFounderIsNotIdle() {
        FounderRow d = row(adminService.getMatrix(cohortId, null), founderD);

        assertThat(d.lastSeenAt()).isNull();
        assertThat(d.attentionFlags())
                .doesNotContain(AttentionFlag.IDLE)
                .contains(AttentionFlag.OVERDUE_TASKS);
    }

    /* --------------------------------------------------------------- helpers */

    private FounderRow row(CohortMatrixResponse matrix, User founder) {
        return matrix.rows().stream()
                .filter(r -> r.userId().equals(founder.getId()))
                .findFirst().orElseThrow();
    }

    private User saveMember(String email) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return users.saveAndFlush(user);
    }

    private UUID insertModule(String name, String pillarLabel, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_modules (id, cohort_id, name, pillar_label, lock_mode, position)
                VALUES (?, ?, ?, ?, 'UNLOCKED', ?)
                """, id, cohortId, name, pillarLabel, position);
        return id;
    }

    private UUID insertTask(UUID moduleId, String name, String type, UUID refId,
            String milestoneRole, int position, boolean dueYesterday) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, position,
                                           task_type, ref_id, milestone_role, due_date)
                VALUES (?, ?, ?, 'LIVE', ?, ?, ?, ?, %s)
                """.formatted(dueYesterday ? "current_date - 1" : "NULL"),
                id, moduleId, name, position, type, refId, milestoneRole);
        return id;
    }
}
