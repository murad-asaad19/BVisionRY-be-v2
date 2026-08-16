package com.bvisionry.programflow.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.programaccess.ProgramAudience;
import com.bvisionry.common.programaccess.TaskCompletion;
import com.bvisionry.common.progress.CourseProgressSql;

/**
 * Cross-feature reads AND the open-endpoint's ensure-writes for the typed task
 * spine (redesign spec §1/§2.1), raw SQL through
 * {@link NamedParameterJdbcTemplate} — same stance as
 * {@code founderprofile.FounderProfileReadRepository}: the ArchUnit ratchet
 * forbids new feature→feature imports, so this class depends on the schema of
 * the owning slices (enrollment, exercise, assessment, workshops, survey).
 *
 * <p><strong>Writes.</strong> The open endpoint must ensure a prerequisite row
 * exists (enrollment / exercise assignment+submission / assessment
 * assignment + tagged submission) and return synchronously, so the inserts
 * live here as raw SQL rather than in the owning slice's service. They
 * replicate only the structural invariants (unique constraints, defaults,
 * starter-row seeding); audit rows and "assigned to you" push notifications
 * are deliberately NOT replicated — the member opened the work themselves.
 * All inserts are idempotent via the owning tables' unique constraints
 * (ON CONFLICT DO NOTHING + re-select).
 *
 * <p><strong>Tenancy:</strong> every entry point is keyed on member/cohort ids
 * the CALLER has already proved are in scope ({@code MyProgramService}'s
 * enrolled-cohort + audience access checks).
 */
@Repository
public class TaskSpineRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TaskSpineRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------- per-type status reads */

    public record CourseStateRow(UUID userId, UUID courseId, String status, int progressPct,
                                 Instant enrolledAt, Instant completedAt) {}

    /**
     * Enrollment state per (member, course) for COURSE task rows. An
     * admin-CANCELLED row reads as "not enrolled" (the member sees not
     * started; {@link #ensureEnrollment} reactivates it on open).
     */
    public List<CourseStateRow> courseStates(Collection<UUID> userIds, Collection<UUID> courseIds) {
        if (userIds.isEmpty() || courseIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT e.user_id, e.course_id, e.status, %1$s AS progress_pct,
                       e.enrolled_at, e.completed_at
                FROM enrollment e
                WHERE e.user_id IN (:userIds) AND e.course_id IN (:courseIds)
                  AND e.status <> 'CANCELLED'
                """.formatted(CourseProgressSql.LIVE_PROGRESS_PCT),
                new MapSqlParameterSource("userIds", userIds).addValue("courseIds", courseIds),
                (rs, i) -> new CourseStateRow(rs.getObject("user_id", UUID.class),
                        rs.getObject("course_id", UUID.class), rs.getString("status"),
                        rs.getInt("progress_pct"), instant(rs, "enrolled_at"),
                        instant(rs, "completed_at")));
    }

    public record ExerciseStateRow(UUID userId, UUID taskId, UUID submissionId, String status,
                                   Instant submittedAt, Instant reviewedAt) {}

    /**
     * The member's exercise assignment TAGGED to each EXERCISE task (V173), and
     * its working copy. Keyed by TASK id, not by template: two cohorts may hand
     * out the same template, and each owns its own copy of the work — doing it
     * in one must not mark the other done. One assignment per (member, task)
     * and one submission per assignment, both DB-unique, so this is at most one
     * row per pair.
     */
    public List<ExerciseStateRow> exerciseStates(Collection<UUID> userIds,
                                                 Collection<UUID> taskIds) {
        if (userIds.isEmpty() || taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT ea.user_id, ea.program_task_id, es.id AS submission_id, es.status,
                       es.submitted_at, es.reviewed_at
                FROM exercise_assignments ea
                JOIN exercise_submissions es ON es.assignment_id = ea.id
                WHERE ea.user_id IN (:userIds) AND ea.program_task_id IN (:taskIds)
                """,
                new MapSqlParameterSource("userIds", userIds).addValue("taskIds", taskIds),
                (rs, i) -> new ExerciseStateRow(rs.getObject("user_id", UUID.class),
                        rs.getObject("program_task_id", UUID.class),
                        rs.getObject("submission_id", UUID.class), rs.getString("status"),
                        instant(rs, "submitted_at"), instant(rs, "reviewed_at")));
    }

    public record AssessmentStateRow(UUID userId, UUID taskId, UUID submissionId, String status,
                                     Instant submittedAt, Instant evaluatedAt, BigDecimal score) {}

    /**
     * A cohort assessment REFERENCES the member's own assessment history — it
     * never asks for a second sitting of an instrument they have already sat.
     *
     * <p>So a milestone resolves to the TAGGED submission when there is one
     * (the tag, not the pipeline, identifies the milestone — that is what makes
     * a same-instrument pair resolvable), and otherwise ADOPTS a sitting the
     * member took outside this cohort:
     * <ul>
     *   <li>BASELINE adopts their EARLIEST evaluated sitting of the referenced
     *       pipeline, whenever it happened. Intake semantics: the reading the
     *       programme is measured from predates the programme, which is the
     *       ordinary case for a cohort built around an assessment people have
     *       already taken. Retakes never move it.</li>
     *   <li>DISTANCE's floor depends on the pair's shape. Shared instrument
     *       (the cohort's BASELINE references the SAME pipeline): earliest
     *       evaluated sitting AFTER the cohort launched, and never the sitting
     *       BASELINE would take — without the launch floor a pre-programme
     *       sitting could be reported as the "after"; without the exclusion
     *       one sitting could satisfy both roles and report a delta of zero
     *       against itself. Own instrument (readiness → distance): the
     *       instrument itself marks the sitting as the "after" measurement,
     *       so the floor is the member's BASELINE READING, not the launch —
     *       a distance instrument sat before the cohort existed is still the
     *       distance reading, but one sat before the baseline would pair a
     *       "to" that predates its "from". No baseline reading, no floor.</li>
     *   <li>CHECK-IN adopts nothing. A trajectory reading is a point in time
     *       inside the programme, so borrowing an outside sitting for it would
     *       invent a data point on the curve.</li>
     * </ul>
     * Adoption is resolved on READ, not written: the journey must show the task
     * done before the member ever opens it, and a read has no business writing.
     * {@code adoptableSubmissionId} applies the identical rule on the open path,
     * where the tag IS written — so the two can never disagree.
     */
    public List<AssessmentStateRow> assessmentStates(Collection<UUID> userIds,
                                                     Collection<UUID> taskIds) {
        if (userIds.isEmpty() || taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                WITH tagged AS (
                    SELECT DISTINCT ON (s.user_id, s.program_task_id)
                           s.user_id, s.program_task_id AS task_id, s.id AS submission_id,
                           s.status, s.submitted_at, s.evaluated_at
                    FROM submissions s
                    WHERE s.user_id IN (:userIds) AND s.program_task_id IN (:taskIds)
                    ORDER BY s.user_id, s.program_task_id, s.created_at DESC
                ),
                adopted AS (
                    SELECT DISTINCT ON (c.user_id, c.task_id)
                           c.user_id, c.task_id, c.submission_id,
                           c.status, c.submitted_at, c.evaluated_at
                    FROM (%s) c
                    WHERE NOT EXISTS (SELECT 1 FROM tagged tg
                                       WHERE tg.user_id = c.user_id AND tg.task_id = c.task_id)
                    ORDER BY c.user_id, c.task_id, c.evaluated_at
                )
                SELECT r.user_id, r.task_id, r.submission_id, r.status,
                       r.submitted_at, r.evaluated_at,
                       os.overall_score_percentage AS score
                FROM (SELECT * FROM tagged UNION ALL SELECT * FROM adopted) r
                LEFT JOIN overall_summaries os ON os.submission_id = r.submission_id
                """.formatted(ADOPTABLE_SITTINGS),
                new MapSqlParameterSource("userIds", userIds).addValue("taskIds", taskIds),
                (rs, i) -> new AssessmentStateRow(rs.getObject("user_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("submission_id", UUID.class), rs.getString("status"),
                        instant(rs, "submitted_at"), instant(rs, "evaluated_at"),
                        rs.getBigDecimal("score")));
    }

    /**
     * Every sitting a milestone task could adopt, by the rule documented on
     * {@link #assessmentStates}. Written once and shared by the read and the
     * open path; callers narrow it to the earliest per (member, task).
     *
     * <p>{@code program_task_id IS NULL} does the heavy lifting: a sitting that
     * some task has already claimed is nobody else's to take, so the moment
     * BASELINE tags one, DISTANCE stops seeing it.
     */
    private static final String ADOPTABLE_SITTINGS = """
            SELECT s.user_id, t.id AS task_id, s.id AS submission_id,
                   s.status, s.submitted_at, s.evaluated_at
            FROM submissions s
            JOIN assignments a ON a.id = s.assignment_id
            JOIN program_tasks t ON t.ref_id = a.pipeline_id
                 AND t.task_type = 'ASSESSMENT'
                 AND t.milestone_role IN ('BASELINE', 'DISTANCE')
            JOIN program_modules m ON m.id = t.module_id
            JOIN cohorts co ON co.id = m.cohort_id
            JOIN cohort_members cm ON cm.cohort_id = co.id AND cm.user_id = s.user_id
            WHERE s.user_id IN (:userIds) AND t.id IN (:taskIds)
              AND s.status = 'EVALUATED'
              AND s.program_task_id IS NULL
              AND (t.milestone_role = 'BASELINE'
                   -- DISTANCE on a SHARED instrument (this cohort's BASELINE
                   -- references the same pipeline): launch floor plus
                   -- BASELINE's earliest-sitting carve-out - one sitting must
                   -- never satisfy both roles.
                   OR (EXISTS (SELECT 1
                               FROM program_tasks bt
                               JOIN program_modules bm ON bm.id = bt.module_id
                               WHERE bm.cohort_id = co.id
                                 AND bt.task_type = 'ASSESSMENT'
                                 AND bt.milestone_role = 'BASELINE'
                                 AND bt.ref_id = a.pipeline_id)
                       AND co.launched_at IS NOT NULL
                       AND s.evaluated_at > co.launched_at
                       AND s.id <> (SELECT e.id
                                    FROM submissions e
                                    JOIN assignments ea ON ea.id = e.assignment_id
                                    WHERE e.user_id = s.user_id
                                      AND ea.pipeline_id = a.pipeline_id
                                      AND e.status = 'EVALUATED'
                                    ORDER BY e.evaluated_at, e.created_at
                                    LIMIT 1))
                   -- DISTANCE on its OWN instrument: the instrument already
                   -- marks the sitting as the "after" measurement, so the
                   -- launch date is the wrong floor - the member's baseline
                   -- reading is the right one. A sitting BEFORE the baseline
                   -- would pair a "to" that predates its "from"; without any
                   -- baseline reading there is nothing to be after.
                   OR (NOT EXISTS (SELECT 1
                                   FROM program_tasks bt
                                   JOIN program_modules bm ON bm.id = bt.module_id
                                   WHERE bm.cohort_id = co.id
                                     AND bt.task_type = 'ASSESSMENT'
                                     AND bt.milestone_role = 'BASELINE'
                                     AND bt.ref_id = a.pipeline_id)
                       AND s.evaluated_at > COALESCE(
                               (SELECT min(bs.evaluated_at)
                                FROM submissions bs
                                JOIN assignments bsa ON bsa.id = bs.assignment_id
                                JOIN program_tasks bt ON bt.ref_id = bsa.pipeline_id
                                JOIN program_modules bm ON bm.id = bt.module_id
                                WHERE bm.cohort_id = co.id
                                  AND bt.task_type = 'ASSESSMENT'
                                  AND bt.milestone_role = 'BASELINE'
                                  AND bs.user_id = s.user_id
                                  AND bs.status = 'EVALUATED'),
                               '-infinity'::timestamptz)))
            """;

    /**
     * The sitting this milestone task should adopt for the member, or empty when
     * there is none to adopt and a fresh one has to be taken. Same rule as the
     * journey read — see {@link #assessmentStates}.
     */
    public Optional<UUID> adoptableSubmissionId(UUID userId, UUID taskId) {
        return jdbc.query("SELECT submission_id FROM (%s) c ORDER BY c.evaluated_at LIMIT 1"
                        .formatted(ADOPTABLE_SITTINGS),
                new MapSqlParameterSource("userIds", List.of(userId))
                        .addValue("taskIds", List.of(taskId)),
                (rs, i) -> rs.getObject("submission_id", UUID.class))
                .stream().findFirst();
    }

    /** Claims an adopted sitting for the milestone, so every later read agrees. */
    public void tagSubmission(UUID submissionId, UUID taskId) {
        jdbc.update("""
                UPDATE submissions SET program_task_id = :taskId
                 WHERE id = :submissionId AND program_task_id IS NULL
                """,
                new MapSqlParameterSource("submissionId", submissionId).addValue("taskId", taskId));
    }

    public record BaselineFallbackRow(UUID userId, UUID pipelineId, UUID submissionId,
                                      Instant submittedAt, Instant evaluatedAt, BigDecimal score) {}

    /**
     * The member's EARLIEST evaluated UNTAGGED submission per (member,
     * pipeline) — the pre-spine visibility fallback for BASELINE milestone
     * tasks with no tagged submission. Ordering mirrors the comparison slice's
     * baseline resolution exactly (evaluated_at ASC NULLS LAST, created_at
     * ASC), so the journey band and the stored comparison agree on what the
     * baseline is.
     *
     * <p><strong>Invariant: {@code program_task_id IS NULL} only.</strong>
     * Since spec §13 a member can sit in several cohorts that share a pipeline,
     * so a submission already tagged to one cohort's milestone task must never
     * be adoptable as another cohort's baseline — without the filter, cohort
     * A's baseline score showed up on cohort B's untouched baseline band. The
     * fallback exists purely for submissions belonging to NO cohort task
     * (pre-spine / directly-assigned work); anything tagged is resolved by
     * {@link #assessmentStates} against its own task id.
     */
    public List<BaselineFallbackRow> earliestEvaluatedForPipelines(Collection<UUID> userIds,
                                                                   Collection<UUID> pipelineIds) {
        if (userIds.isEmpty() || pipelineIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT DISTINCT ON (s.user_id, a.pipeline_id)
                       s.user_id, a.pipeline_id, s.id AS submission_id,
                       s.submitted_at, s.evaluated_at, os.overall_score_percentage AS score
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                LEFT JOIN overall_summaries os ON os.submission_id = s.id
                WHERE s.user_id IN (:userIds) AND a.pipeline_id IN (:pipelineIds)
                  AND s.status = 'EVALUATED'
                  AND s.program_task_id IS NULL
                ORDER BY s.user_id, a.pipeline_id, s.evaluated_at ASC NULLS LAST, s.created_at ASC
                """,
                new MapSqlParameterSource("userIds", userIds).addValue("pipelineIds", pipelineIds),
                (rs, i) -> new BaselineFallbackRow(rs.getObject("user_id", UUID.class),
                        rs.getObject("pipeline_id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        instant(rs, "submitted_at"), instant(rs, "evaluated_at"),
                        rs.getBigDecimal("score")));
    }

    public record ParticipationRow(UUID userId, UUID taskId, Instant completedAt) {}

    /**
     * Members who answered each SURVEY task's survey (SURVEY done-state), keyed
     * by TASK id (V173) for the same reason as {@link #exerciseStates}: two
     * cohorts may point at one survey and each must be answered on its own.
     * Only the in-app program-task route tags a response, so a public-link or
     * post-assessment answer to the same survey no longer completes cohort work.
     */
    public List<ParticipationRow> surveyParticipation(Collection<UUID> userIds,
                                                      Collection<UUID> taskIds) {
        if (userIds.isEmpty() || taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT sr.respondent_user_id AS user_id, sr.program_task_id AS task_id,
                       max(sr.submitted_at) AS completed_at
                FROM survey_responses sr
                WHERE sr.respondent_user_id IN (:userIds) AND sr.program_task_id IN (:taskIds)
                GROUP BY sr.respondent_user_id, sr.program_task_id
                """,
                new MapSqlParameterSource("userIds", userIds).addValue("taskIds", taskIds),
                this::participationRow);
    }

    private ParticipationRow participationRow(ResultSet rs, int i) throws SQLException {
        return new ParticipationRow(rs.getObject("user_id", UUID.class),
                rs.getObject("task_id", UUID.class), instant(rs, "completed_at"));
    }

    /* ---------------------------------------------------- direct assignments */

    /**
     * Refs already represented by a LIVE cohort task of {@code taskType} that
     * reaches this member (audience-checked) — direct-assignment rows for
     * these refs are cohort work, not "direct" (spec §2.1 exclusion). COURSE
     * only since V173: a course is per member, so the one enrollment row is
     * shared between the journey and the direct list and has to be matched by
     * ref. Exercises and assessments carry a task TAG instead — see
     * {@link #COVERED_BY_TAGGED_TASK}.
     */
    private static final String COVERED_BY_COHORT_TASK = """
            EXISTS (SELECT 1
                    FROM cohort_members cm
                    JOIN program_modules m ON m.cohort_id = cm.cohort_id
                    JOIN program_tasks t ON t.module_id = m.id
                    WHERE cm.user_id = :memberId AND t.status = 'LIVE'
                      AND t.task_type = %s AND t.ref_id = %s
                      AND """ + ProgramAudience.INCLUDES_USER.formatted(":memberId") + ")";

    /**
     * The row tagged by {@code %s} (an assignment's or submission's
     * {@code program_task_id}) belongs to a LIVE cohort task of this member's —
     * so the cohort surface owns it and the direct list must not repeat it.
     * The tag is proof of representation: audience was enforced when it was
     * created. False for an untagged row, and false again once the task is
     * gone or no longer LIVE, so nothing is ever orphaned out of both surfaces.
     */
    private static final String COVERED_BY_TAGGED_TASK = """
            EXISTS (SELECT 1
                    FROM program_tasks t
                    JOIN program_modules m ON m.id = t.module_id
                    JOIN cohort_members cm ON cm.cohort_id = m.cohort_id
                                          AND cm.user_id = :memberId
                    WHERE t.id = %s AND t.status = 'LIVE')""";

    public record DirectExerciseRow(UUID assignmentId, UUID templateId, String title,
                                    UUID submissionId, String status, Instant deadline,
                                    Instant assignedAt, Instant submittedAt, Instant reviewedAt) {}

    /**
     * The member's exercise assignments that no cohort task owns. Since V173 an
     * exercise opened from a cohort task gets its OWN assignment, tagged with
     * that task, so "covered" is the tag — not a template match. That is what
     * makes a directly-assigned exercise appear exactly once: it keeps its
     * untagged row here even when a cohort task happens to hand out the same
     * template, and the cohort task tracks its own copy.
     */
    public List<DirectExerciseRow> directExercises(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT ea.id, ea.template_id, et.name AS title, es.id AS submission_id, es.status,
                       ea.deadline, ea.created_at AS assigned_at, es.submitted_at, es.reviewed_at
                FROM exercise_assignments ea
                JOIN exercise_templates et ON et.id = ea.template_id
                LEFT JOIN exercise_submissions es ON es.assignment_id = ea.id
                WHERE ea.organization_id = :orgId AND ea.user_id = :memberId
                  AND NOT %s
                ORDER BY ea.created_at DESC
                """.formatted(COVERED_BY_TAGGED_TASK.formatted("ea.program_task_id")),
                params(orgId, memberId),
                (rs, i) -> new DirectExerciseRow(rs.getObject("id", UUID.class),
                        rs.getObject("template_id", UUID.class), rs.getString("title"),
                        rs.getObject("submission_id", UUID.class), rs.getString("status"),
                        instant(rs, "deadline"), instant(rs, "assigned_at"),
                        instant(rs, "submitted_at"), instant(rs, "reviewed_at")));
    }

    public record DirectAssessmentRow(UUID assignmentId, UUID pipelineId, String title,
                                      UUID submissionId, String status, Instant deadline,
                                      Instant assignedAt, Instant submittedAt, Instant evaluatedAt,
                                      BigDecimal score) {}

    /**
     * Member assessment assignments not REPRESENTED by any cohort task
     * (review decision #6), with the latest submission's state. An assignment
     * is represented — and therefore excluded — only when (a) one of its
     * submissions is tagged to a LIVE cohort task of the member's (the tag is
     * proof of representation; audience was enforced when the tag was
     * created), or (b) a BASELINE milestone task references its pipeline AND
     * the member has an evaluated UNTAGGED submission — the exact condition
     * under which the journey's pre-spine baseline fallback will display it
     * ({@link #earliestEvaluatedForPipelines}, tagged rows excluded there too,
     * so the two predicates stay mirrored and no assignment can fall between
     * them and vanish from every surface). Any other assignment on the
     * pipeline stays listed as direct work.
     */
    public List<DirectAssessmentRow> directAssessments(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT a.id, a.pipeline_id, p.name AS title, a.deadline,
                       a.created_at AS assigned_at, s.id AS submission_id, s.status,
                       s.submitted_at, s.evaluated_at, os.overall_score_percentage AS score
                FROM assignments a
                JOIN pipelines p ON p.id = a.pipeline_id
                LEFT JOIN LATERAL (SELECT s2.id, s2.status, s2.submitted_at, s2.evaluated_at
                                   FROM submissions s2
                                   WHERE s2.assignment_id = a.id AND s2.user_id = :memberId
                                   ORDER BY s2.created_at DESC LIMIT 1) s ON true
                LEFT JOIN overall_summaries os ON os.submission_id = s.id
                WHERE a.organization_id = :orgId AND a.user_id = :memberId
                  AND NOT EXISTS (
                      SELECT 1 FROM submissions st
                      WHERE st.assignment_id = a.id AND st.user_id = :memberId
                        AND %2$s)
                  AND NOT EXISTS (
                      SELECT 1 FROM cohort_members cm
                      JOIN program_modules m ON m.cohort_id = cm.cohort_id
                      JOIN program_tasks t ON t.module_id = m.id
                      WHERE cm.user_id = :memberId AND t.status = 'LIVE'
                        AND t.task_type = 'ASSESSMENT' AND t.milestone_role = 'BASELINE'
                        AND t.ref_id = a.pipeline_id
                        AND %1$s
                        AND EXISTS (SELECT 1 FROM submissions se
                                    WHERE se.assignment_id = a.id AND se.user_id = :memberId
                                      AND se.status = 'EVALUATED'
                                      AND se.program_task_id IS NULL))
                ORDER BY a.created_at DESC
                """.formatted(ProgramAudience.INCLUDES_USER.formatted(":memberId"),
                        COVERED_BY_TAGGED_TASK.formatted("st.program_task_id")),
                params(orgId, memberId),
                (rs, i) -> new DirectAssessmentRow(rs.getObject("id", UUID.class),
                        rs.getObject("pipeline_id", UUID.class), rs.getString("title"),
                        rs.getObject("submission_id", UUID.class), rs.getString("status"),
                        instant(rs, "deadline"), instant(rs, "assigned_at"),
                        instant(rs, "submitted_at"), instant(rs, "evaluated_at"),
                        rs.getBigDecimal("score")));
    }

    public record DirectCourseRow(UUID enrollmentId, UUID courseId, String title, String status,
                                  int progressPct, Instant enrolledAt, Instant completedAt,
                                  String source, boolean required, Instant deadline) {}

    /**
     * The member's courses that no cohort task covers - spec section 3's four
     * sources as the journey's "Direct assignments" section sees them.
     *
     * <p>Two halves, and the second is the point: real {@code enrollment} rows,
     * UNION the org rules covering this member that have no row YET. Without the
     * union a required org-wide course would be invisible in the journey until
     * the member happened to open the Library, which is precisely backwards. The
     * row is written on first open ({@link #ensureEnrollment}).
     *
     * <p><strong>Semantics owner:</strong>
     * {@code courseaccess.domain.EffectiveCourses}. This is a deliberate SQL
     * subset of that merge - no AI suggestions (a suggestion is an offer, not
     * work), and the same three rules: an org rule outranks a SELF/AI source but
     * never a DIRECT one, {@code required} is a union, and the earliest deadline
     * binds. Change one, change the other.
     *
     * <p>The UN-materialized half is filtered by visibility + PUBLISHED, matching
     * {@code CourseAccessService#accept}: a claim the member would be refused is
     * a dead row. Materialized rows are not — spec §3's downgrade policy keeps
     * progress visible.
     */
    public List<DirectCourseRow> directCourses(UUID memberId) {
        String notCovered = "NOT " + COVERED_BY_COHORT_TASK.formatted("'COURSE'", "%s");
        return jdbc.query("""
                SELECT e.id, e.course_id, c.title, e.status, %4$s AS progress_pct,
                       e.enrolled_at, e.completed_at,
                       CASE WHEN r.course_id IS NOT NULL AND e.source <> 'DIRECT'
                            THEN 'ORG_RULE' ELSE e.source END AS source,
                       (e.required OR COALESCE(r.required, FALSE)) AS required,
                       LEAST(e.deadline, r.deadline) AS deadline
                FROM enrollment e
                JOIN course c ON c.id = e.course_id
                LEFT JOIN org_course_rules r
                       ON r.course_id = e.course_id
                      AND r.org_id = (SELECT organization_id FROM users WHERE id = :memberId)
                      AND NOT EXISTS (SELECT 1 FROM enrolment_overrides o
                                       WHERE o.user_id = :memberId AND o.course_id = e.course_id)
                WHERE e.user_id = :memberId
                  AND e.status <> 'CANCELLED'
                  AND %1$s
                UNION ALL
                SELECT NULL, r.course_id, c.title, NULL, 0,
                       r.created_at, NULL, 'ORG_RULE', r.required, r.deadline
                FROM org_course_rules r
                JOIN course c ON c.id = r.course_id
                WHERE r.org_id = (SELECT organization_id FROM users WHERE id = :memberId)
                  AND NOT EXISTS (SELECT 1 FROM enrollment e2
                                   WHERE e2.user_id = :memberId AND e2.course_id = r.course_id
                                     AND e2.status <> 'CANCELLED')
                  AND NOT EXISTS (SELECT 1 FROM enrolment_overrides o
                                   WHERE o.user_id = :memberId AND o.course_id = r.course_id)
                  AND c.state = 'PUBLISHED' AND %3$s
                  AND %2$s
                ORDER BY 6 DESC
                """.formatted(notCovered.formatted("e.course_id"), notCovered.formatted("r.course_id"),
                        CourseVisibilityAccess.VISIBLE_TO_ORG.formatted(
                                "(SELECT organization_id FROM users WHERE id = :memberId)"),
                        CourseProgressSql.LIVE_PROGRESS_PCT),
                new MapSqlParameterSource("memberId", memberId),
                (rs, i) -> new DirectCourseRow(rs.getObject("id", UUID.class),
                        rs.getObject("course_id", UUID.class), rs.getString("title"),
                        rs.getString("status"), rs.getInt("progress_pct"),
                        instant(rs, "enrolled_at"), instant(rs, "completed_at"),
                        rs.getString("source"), rs.getBoolean("required"),
                        instant(rs, "deadline")));
    }

    /**
     * The cohort-enrolled users who have DONE this task under its own type's
     * rule (shared {@link TaskCompletion} fragment — ProgramRules semantics).
     * The due-reminder job subtracts them from its recipients so no learner is
     * nagged about work any surface already counts as complete.
     */
    public List<UUID> usersDoneWithTask(UUID taskId) {
        return jdbc.query("""
                SELECT cm.user_id
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                JOIN cohort_members cm ON cm.cohort_id = m.cohort_id
                WHERE t.id = :taskId
                  AND %s
                """.formatted(TaskCompletion.DONE_FOR_USER.formatted("cm.user_id")),
                new MapSqlParameterSource("taskId", taskId),
                (rs, i) -> rs.getObject("user_id", UUID.class));
    }

    /**
     * Cohort members with a course past its deadline and unfinished (spec §3:
     * "overdue shows in the journey and the needs-attention strip").
     *
     * <p>Both halves of the enrollment model: real rows (served by
     * {@code ix_enrollment_deadline}) and org rules nobody has opened yet, minus
     * exclusions. Required AND optional — §3 says overdue shows, and does not
     * qualify that by the flag.
     */
    public java.util.Set<UUID> membersWithOverdueCourses(UUID cohortId) {
        return new java.util.HashSet<>(jdbc.query("""
                SELECT cm.user_id
                FROM cohort_members cm
                WHERE cm.cohort_id = :cohortId
                  AND (EXISTS (SELECT 1 FROM enrollment e
                                WHERE e.user_id = cm.user_id
                                  AND e.status NOT IN ('CANCELLED', 'COMPLETED')
                                  AND e.deadline IS NOT NULL AND e.deadline < NOW())
                    OR EXISTS (SELECT 1
                                 FROM org_course_rules r
                                 JOIN users u ON u.id = cm.user_id AND u.organization_id = r.org_id
                                WHERE r.deadline IS NOT NULL AND r.deadline < NOW()
                                  AND NOT EXISTS (SELECT 1 FROM enrolment_overrides o
                                                   WHERE o.user_id = cm.user_id
                                                     AND o.course_id = r.course_id)
                                  AND NOT EXISTS (SELECT 1 FROM enrollment e2
                                                   WHERE e2.user_id = cm.user_id
                                                     AND e2.course_id = r.course_id
                                                     AND e2.status IN ('CANCELLED', 'COMPLETED'))))
                """,
                new MapSqlParameterSource("cohortId", cohortId),
                (rs, i) -> rs.getObject("user_id", UUID.class)));
    }

    /* ------------------------------------------------- validation probes */

    /**
     * The task's reference exists in its owning slice (validation, review #7).
     * A LIVE COURSE task additionally requires the course to be published.
     * Spec §13: authoring is platform-level, so there is no org to check here —
     * a course some assigned org cannot SEE is handled at read time (it stops
     * gating for that org's members and renders unavailable). LESSON has no ref.
     */
    public boolean refExists(com.bvisionry.programflow.domain.ProgramTaskType type, UUID refId,
                             boolean live) {
        String sql = switch (type) {
            case LESSON -> null;
            case COURSE -> live
                    ? "SELECT EXISTS (SELECT 1 FROM course c WHERE c.id = :id AND c.state = 'PUBLISHED')"
                    : "SELECT EXISTS (SELECT 1 FROM course c WHERE c.id = :id)";
            case EXERCISE -> "SELECT EXISTS (SELECT 1 FROM exercise_templates WHERE id = :id)";
            case ASSESSMENT -> "SELECT EXISTS (SELECT 1 FROM pipelines WHERE id = :id)";
            case SURVEY -> "SELECT EXISTS (SELECT 1 FROM surveys WHERE id = :id)";
        };
        if (sql == null) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,
                new MapSqlParameterSource("id", refId), Boolean.class));
    }

    /** Any submission carries this task's milestone tag → the ref is frozen (review #7b). */
    public boolean hasTaggedSubmissions(UUID taskId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM submissions WHERE program_task_id = :taskId)",
                new MapSqlParameterSource("taskId", taskId), Boolean.class));
    }

    /* --------------------------------------------------- open ensure-writes */

    /** A LIVE (non-CANCELLED) enrollment exists — an admin-removed row doesn't count. */
    public boolean enrollmentExists(UUID userId, UUID courseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM enrollment
                               WHERE user_id = :userId AND course_id = :courseId
                                 AND status <> 'CANCELLED')
                """,
                new MapSqlParameterSource("userId", userId).addValue("courseId", courseId),
                Boolean.class));
    }

    /**
     * Idempotent enrollment on task open (spec §3: enrollment happens on open,
     * not render). An admin-CANCELLED row keeps its unique-constraint slot, so
     * the insert no-ops and the UPDATE hands the member their course back with
     * progress intact — status derived exactly like
     * {@code EnrollmentService.reactivateIfRemoved} (COMPLETED when they had
     * finished, else ACTIVE).
     *
     * <p><strong>Claim-aware source</strong> (spec §3): the row records the
     * STRONGEST claim that already covers this member, so a rule-derived course
     * materializes as ORG_RULE and its rule can still unassign it —
     * "unassignment is one delete" has to hold for the members who opened it
     * too. Only a course NO rule covers is DIRECT: a cohort task is an admin
     * putting it in front of this member by name. DO NOTHING on conflict, so a
     * member who already had the course keeps whatever story it carried.
     */
    public void ensureEnrollment(UUID userId, UUID courseId) {
        MapSqlParameterSource params =
                new MapSqlParameterSource("userId", userId).addValue("courseId", courseId);
        jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, status, source)
                VALUES (:userId, :courseId, 'ACTIVE',
                        CASE WHEN EXISTS (SELECT 1
                                            FROM org_course_rules r
                                            JOIN users u ON u.id = :userId
                                                        AND u.organization_id = r.org_id
                                           WHERE r.course_id = :courseId)
                             THEN 'ORG_RULE' ELSE 'DIRECT' END)
                ON CONFLICT ON CONSTRAINT uq_enrollment_user_course DO NOTHING
                """, params);
        jdbc.update("""
                UPDATE enrollment
                SET status = CASE WHEN completed_at IS NOT NULL THEN 'COMPLETED' ELSE 'ACTIVE' END
                WHERE user_id = :userId AND course_id = :courseId AND status = 'CANCELLED'
                """, params);
    }

    /**
     * The member's exercise submission tagged to this cohort task, if any —
     * and only when it is a submission for the task's CURRENT template.
     *
     * <p>Keying on the task alone stranded members after an admin re-pointed a
     * task at a different exercise: the stale submission kept winning, so the
     * member opened "Frustration" and got the Appreciation brief and columns
     * forever. Returning empty here lets
     * {@link #ensureExerciseSubmission} reconcile it.
     */
    public Optional<UUID> findExerciseSubmissionId(UUID userId, UUID taskId, UUID templateId) {
        return jdbc.query("""
                SELECT es.id
                FROM exercise_assignments ea
                JOIN exercise_submissions es ON es.assignment_id = ea.id
                WHERE ea.user_id = :userId AND ea.program_task_id = :taskId
                  AND ea.template_id = :templateId
                """,
                new MapSqlParameterSource("userId", userId)
                        .addValue("taskId", taskId)
                        .addValue("templateId", templateId),
                (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    /**
     * The member's exercise submission for this cohort task, regardless of which
     * template it was created against. Used ONLY for read-only review on a
     * FINISHED cohort: there {@link #ensureExerciseSubmission} cannot run to
     * reconcile a re-pointed (or template-deleted) task, so showing the member's
     * actual work beats erroring. On an active cohort the template-keyed lookup
     * above stays authoritative. (program_task_id, user_id) is unique, so this
     * returns the single row if one exists.
     */
    public Optional<UUID> findAnyExerciseSubmissionId(UUID userId, UUID taskId) {
        return jdbc.query("""
                SELECT es.id
                FROM exercise_assignments ea
                JOIN exercise_submissions es ON es.assignment_id = ea.id
                WHERE ea.user_id = :userId AND ea.program_task_id = :taskId
                """,
                new MapSqlParameterSource("userId", userId).addValue("taskId", taskId),
                (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    /**
     * Ensures the member's exercise assignment + working-copy submission for
     * THIS cohort task exist (assigned_by = the member: they opened the task
     * themselves) and seeds the template's starter rows exactly like
     * {@code ExerciseAssignmentService.createAssignmentForMember} does.
     *
     * <p>The assignment carries the task tag (V173), so a second cohort handing
     * out the same template gets its own copy of the work. Race-safe like the
     * other ensure-writes: uq_exercise_assignments_program_task_user turns the
     * double-open loser into a no-op and the re-select returns the winner's row.
     */
    public UUID ensureExerciseSubmission(UUID orgId, UUID templateId, UUID userId, UUID taskId) {
        MapSqlParameterSource params = exerciseParams(orgId, templateId, userId)
                .addValue("taskId", taskId);
        // An admin re-pointed this task at a different exercise. Carry an
        // UNTOUCHED assignment across rather than stranding the member on the
        // old template — (program_task_id, user_id) is unique, so a plain
        // insert would be a no-op and the stale row would keep winning.
        // A submission the member has actually typed into is left alone: their
        // work outranks the rename, and the admin sees the mismatch instead.
        int repointed = jdbc.update("""
                UPDATE exercise_assignments ea
                SET template_id = :templateId
                WHERE ea.user_id = :userId AND ea.program_task_id = :taskId
                  AND ea.template_id <> :templateId
                  AND EXISTS (SELECT 1 FROM exercise_submissions es
                              WHERE es.assignment_id = ea.id
                                AND es.status = 'IN_PROGRESS'
                                AND es.submitted_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM exercise_submissions es
                                  JOIN exercise_rows er ON er.submission_id = es.id
                                  WHERE es.assignment_id = ea.id
                                    AND er.is_starter = false)
                """, params);
        if (repointed > 0) {
            // The old template's starter rows are scaffolding for columns that
            // no longer exist; reseed below from the new template.
            jdbc.update("""
                    DELETE FROM exercise_rows er
                    USING exercise_submissions es, exercise_assignments ea
                    WHERE er.submission_id = es.id AND es.assignment_id = ea.id
                      AND ea.user_id = :userId AND ea.program_task_id = :taskId
                    """, params);
        }
        jdbc.update("""
                INSERT INTO exercise_assignments (template_id, organization_id, user_id,
                                                  assigned_by, program_task_id)
                VALUES (:templateId, :orgId, :userId, :userId, :taskId)
                ON CONFLICT (program_task_id, user_id) WHERE program_task_id IS NOT NULL
                DO NOTHING
                """, params);
        UUID assignmentId = jdbc.queryForObject("""
                SELECT id FROM exercise_assignments
                WHERE user_id = :userId AND program_task_id = :taskId
                """, params, UUID.class);

        int created = jdbc.update("""
                INSERT INTO exercise_submissions (assignment_id, user_id)
                VALUES (:assignmentId, :userId)
                ON CONFLICT (assignment_id) DO NOTHING
                """,
                new MapSqlParameterSource("assignmentId", assignmentId).addValue("userId", userId));
        UUID submissionId = jdbc.queryForObject(
                "SELECT id FROM exercise_submissions WHERE assignment_id = :assignmentId",
                new MapSqlParameterSource("assignmentId", assignmentId), UUID.class);
        if (created > 0 || repointed > 0) {
            jdbc.update("""
                    INSERT INTO exercise_rows (submission_id, display_order, cells, is_starter)
                    SELECT :submissionId, a.ord - 1, a.elem, true
                    FROM exercise_templates et,
                         jsonb_array_elements(et.starter_rows) WITH ORDINALITY AS a(elem, ord)
                    WHERE et.id = :templateId AND et.starter_rows IS NOT NULL
                    """,
                    new MapSqlParameterSource("submissionId", submissionId)
                            .addValue("templateId", templateId));
        }
        return submissionId;
    }

    /** The member's newest submission tagged to this milestone task, if any. */
    public Optional<UUID> findTaggedSubmissionId(UUID userId, UUID taskId) {
        return jdbc.query("""
                SELECT id FROM submissions
                WHERE user_id = :userId AND program_task_id = :taskId
                ORDER BY created_at DESC LIMIT 1
                """,
                new MapSqlParameterSource("userId", userId).addValue("taskId", taskId),
                (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    /**
     * Ensures the member's assessment assignment for the pipeline exists and
     * creates the IN_PROGRESS submission tagged with the milestone task id.
     * Milestone submissions deliberately bypass the assignment's
     * {@code max_check_ins} cap — the cohort's task schedule IS the cap.
     */
    public UUID createTaggedSubmission(UUID orgId, UUID pipelineId, UUID userId, UUID taskId) {
        jdbc.update("""
                INSERT INTO assignments (pipeline_id, organization_id, user_id, assigned_by)
                VALUES (:pipelineId, :orgId, :userId, :userId)
                ON CONFLICT (organization_id, pipeline_id, user_id) WHERE user_id IS NOT NULL
                DO NOTHING
                """,
                new MapSqlParameterSource("pipelineId", pipelineId).addValue("orgId", orgId)
                        .addValue("userId", userId));
        UUID assignmentId = jdbc.queryForObject("""
                SELECT id FROM assignments
                WHERE organization_id = :orgId AND pipeline_id = :pipelineId AND user_id = :userId
                """,
                new MapSqlParameterSource("pipelineId", pipelineId).addValue("orgId", orgId)
                        .addValue("userId", userId),
                UUID.class);
        // Race-safe like the other ensure-writes: uq_submissions_user_program_task
        // makes the double-open loser a no-op, and the re-select returns the
        // winner's row.
        jdbc.update("""
                INSERT INTO submissions (assignment_id, user_id, status, program_task_id)
                VALUES (:assignmentId, :userId, 'IN_PROGRESS', :taskId)
                ON CONFLICT (user_id, program_task_id) WHERE program_task_id IS NOT NULL
                DO NOTHING
                """,
                new MapSqlParameterSource("assignmentId", assignmentId).addValue("userId", userId)
                        .addValue("taskId", taskId));
        return findTaggedSubmissionId(userId, taskId).orElseThrow();
    }

    /* ---------------------------------------------------------------- helpers */

    private static MapSqlParameterSource params(UUID orgId, UUID memberId) {
        return new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId);
    }

    private static MapSqlParameterSource exerciseParams(UUID orgId, UUID templateId, UUID userId) {
        return new MapSqlParameterSource("orgId", orgId).addValue("templateId", templateId)
                .addValue("userId", userId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
