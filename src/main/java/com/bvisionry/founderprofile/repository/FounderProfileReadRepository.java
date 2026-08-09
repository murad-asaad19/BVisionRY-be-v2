package com.bvisionry.founderprofile.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.programaccess.ProgramAudience;

/**
 * Cross-feature reads for the shared founder profile (redesign spec §2.4),
 * raw SQL through {@link NamedParameterJdbcTemplate} — same stance as
 * {@code coaching.CoachingReadRepository} and {@code organization.MemberCourseRepository}:
 * the ArchUnit ratchet forbids new feature→feature imports, so this class
 * depends on the schema and imports no other feature's types.
 *
 * <p><strong>Tenancy:</strong> every entry point either carries
 * {@code orgId} in its predicate or is keyed on a member id the CALLER has
 * already proved is in-org and visible (controllers gate first: org guard for
 * admins, {@code CoachAccess} for coaches; the service then re-anchors on
 * {@link #member}, which is org-scoped SQL).
 *
 * <p><strong>Privacy invariant (§2.4):</strong> no query in this class touches
 * the {@code answers} table. Scores, labels and timestamps only.
 */
@Repository
public class FounderProfileReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FounderProfileReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------------------------------------------------------------- header */

    public record MemberRow(UUID id, String name, String email, String role, String status,
                            String userType, Instant lastLoginAt, Instant lastActivityAt) {}

    /**
     * The profile's anchor row, org-scoped — empty means "not your member" →
     * 404. {@code last_activity_at} is the greatest of the member's own action
     * timestamps (GREATEST/MAX ignore NULLs in Postgres); coach review events
     * are deliberately excluded — this is the FOUNDER's last activity.
     */
    public Optional<MemberRow> member(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT u.id, u.name, u.email, u.role, u.status, u.user_type, u.last_login_at,
                       GREATEST(
                           u.last_login_at,
                           (SELECT max(GREATEST(ps.saved_at, ps.submitted_at))
                              FROM program_submissions ps WHERE ps.user_id = u.id),
                           (SELECT max(GREATEST(es.last_saved_at, es.submitted_at))
                              FROM exercise_submissions es WHERE es.user_id = u.id),
                           (SELECT max(GREATEST(s.started_at, s.submitted_at))
                              FROM submissions s WHERE s.user_id = u.id),
                           (SELECT max(GREATEST(e.enrolled_at, e.completed_at))
                              FROM enrollment e WHERE e.user_id = u.id)
                       ) AS last_activity_at
                FROM users u
                WHERE u.id = :memberId AND u.organization_id = :orgId AND u.role = 'MEMBER'
                """,
                params(orgId, memberId),
                (rs, i) -> new MemberRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getString("role"), rs.getString("status"),
                        rs.getString("user_type"), instant(rs, "last_login_at"),
                        instant(rs, "last_activity_at")))
                .stream().findFirst();
    }

    public record CohortRow(UUID id, String name) {}

    public List<CohortRow> cohorts(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT c.id, c.name FROM cohorts c
                JOIN cohort_members cm ON cm.cohort_id = c.id
                WHERE cm.user_id = :memberId AND c.org_id = :orgId
                ORDER BY c.position, c.name
                """,
                params(orgId, memberId),
                (rs, i) -> new CohortRow(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    public record FriPoint(BigDecimal score, Instant evaluatedAt) {}

    /** Evaluated overall scores oldest → newest; latest = FRI, latest − earliest = Δ-so-far. */
    public List<FriPoint> friTrajectory(UUID memberId) {
        return jdbc.query("""
                SELECT os.overall_score_percentage AS score, s.evaluated_at
                FROM submissions s
                JOIN overall_summaries os ON os.submission_id = s.id
                WHERE s.user_id = :memberId AND s.status = 'EVALUATED'
                ORDER BY s.evaluated_at ASC NULLS LAST, s.created_at ASC
                """,
                new MapSqlParameterSource("memberId", memberId),
                (rs, i) -> new FriPoint(rs.getBigDecimal("score"), instant(rs, "evaluated_at")));
    }

    /* ------------------------------------------------------------- work list */

    public record ProgramTaskRow(UUID taskId, String taskName, String cohortName, String moduleName,
                                 LocalDate dueDate, String status, Instant savedAt, Instant submittedAt) {}

    /** LIVE program tasks in the member's cohorts whose module audience includes them. */
    public List<ProgramTaskRow> programTasks(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT t.id, t.name AS task_name, c.name AS cohort_name, m.name AS module_name,
                       t.due_date, ps.status, ps.saved_at, ps.submitted_at
                FROM cohort_members cm
                JOIN cohorts c         ON c.id = cm.cohort_id AND c.org_id = :orgId
                JOIN program_modules m ON m.cohort_id = c.id
                JOIN program_tasks t   ON t.module_id = m.id AND t.status = 'LIVE'
                LEFT JOIN program_submissions ps ON ps.task_id = t.id AND ps.user_id = :memberId
                WHERE cm.user_id = :memberId
                  AND %s
                ORDER BY c.position, c.name, m.position, t.position
                """.formatted(ProgramAudience.INCLUDES_USER.formatted(":memberId")),
                params(orgId, memberId),
                (rs, i) -> new ProgramTaskRow(rs.getObject("id", UUID.class),
                        rs.getString("task_name"), rs.getString("cohort_name"),
                        rs.getString("module_name"), rs.getObject("due_date", LocalDate.class),
                        rs.getString("status"), instant(rs, "saved_at"), instant(rs, "submitted_at")));
    }

    public record ExerciseRow(UUID assignmentId, String exerciseName, Instant deadline, String status,
                              Instant lastSavedAt, Instant submittedAt, Instant reviewedAt) {}

    /** The member's exercise assignments with submission state + review timestamp. */
    public List<ExerciseRow> exercises(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT ea.id, et.name, ea.deadline, es.status,
                       es.last_saved_at, es.submitted_at, es.reviewed_at
                FROM exercise_assignments ea
                JOIN exercise_templates et ON et.id = ea.template_id
                LEFT JOIN exercise_submissions es ON es.assignment_id = ea.id AND es.user_id = :memberId
                WHERE ea.organization_id = :orgId AND ea.user_id = :memberId
                ORDER BY ea.created_at DESC
                """,
                params(orgId, memberId),
                (rs, i) -> new ExerciseRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        instant(rs, "deadline"), rs.getString("status"),
                        instant(rs, "last_saved_at"), instant(rs, "submitted_at"),
                        instant(rs, "reviewed_at")));
    }

    public record CourseRow(UUID courseId, String title, String status, int progressPct,
                            Instant enrolledAt, Instant completedAt, String pillarName, boolean removed) {}

    /**
     * The member's course enrolments with the WHY: the newest ENROLLED
     * auto-enrolment ledger row names the pillar whose weak band asked for it
     * (→ AI source); NULL means self/manual. Same derivation as
     * {@code organization.MemberCourseRepository}. Keyed on a member id the
     * caller already proved is in-org.
     */
    public List<CourseRow> courses(UUID memberId) {
        return jdbc.query("""
                SELECT c.id, c.title, e.status, e.progress_pct, e.enrolled_at, e.completed_at,
                       (SELECT p.name
                          FROM auto_enrolments a
                          JOIN pillars p ON p.id = a.pillar_id
                         WHERE a.user_id = e.user_id AND a.course_id = e.course_id
                           AND a.outcome = 'ENROLLED'
                         ORDER BY a.created_at DESC, a.id ASC
                         LIMIT 1) AS pillar_name,
                       (o.user_id IS NOT NULL) AS removed
                FROM enrollment e
                JOIN course c ON c.id = e.course_id
                LEFT JOIN enrolment_overrides o ON o.user_id = e.user_id AND o.course_id = e.course_id
                WHERE e.user_id = :memberId
                ORDER BY e.enrolled_at DESC, c.title ASC
                """,
                new MapSqlParameterSource("memberId", memberId),
                (rs, i) -> new CourseRow(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("status"), rs.getInt("progress_pct"),
                        instant(rs, "enrolled_at"), instant(rs, "completed_at"),
                        rs.getString("pillar_name"), rs.getBoolean("removed")));
    }

    public record AssessmentRow(UUID assignmentId, UUID submissionId, String pipelineName,
                                Instant deadline, String status, Instant startedAt,
                                Instant submittedAt, Instant evaluatedAt, BigDecimal score) {}

    /**
     * One row per assessment submission (check-ins produce several), plus a
     * bare TODO row for an assignment never started. Scores come from the
     * evaluated overall summary — never from answers.
     */
    public List<AssessmentRow> assessments(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT a.id AS assignment_id, s.id AS submission_id, p.name AS pipeline_name,
                       COALESCE(s.deadline_override, a.deadline) AS deadline,
                       s.status, s.started_at, s.submitted_at, s.evaluated_at,
                       os.overall_score_percentage AS score
                FROM assignments a
                JOIN pipelines p ON p.id = a.pipeline_id
                LEFT JOIN submissions s ON s.assignment_id = a.id AND s.user_id = :memberId
                LEFT JOIN overall_summaries os ON os.submission_id = s.id
                WHERE a.organization_id = :orgId AND a.user_id = :memberId
                ORDER BY a.created_at DESC, s.started_at DESC NULLS LAST
                """,
                params(orgId, memberId),
                (rs, i) -> new AssessmentRow(rs.getObject("assignment_id", UUID.class),
                        rs.getObject("submission_id", UUID.class), rs.getString("pipeline_name"),
                        instant(rs, "deadline"), rs.getString("status"), instant(rs, "started_at"),
                        instant(rs, "submitted_at"), instant(rs, "evaluated_at"),
                        rs.getBigDecimal("score")));
    }

    /* ------------------------------------------------- pillar snapshot, notes */

    public record PillarRow(String pillarName, BigDecimal scorePercentage, String maturityLabel,
                            Instant evaluatedAt) {}

    /** Latest evaluated assessment, one row per pillar (same anchor as the coach console read). */
    public List<PillarRow> pillarScores(UUID memberId) {
        return jdbc.query("""
                SELECT p.name, pe.score_percentage, pe.maturity_label, pe.evaluated_at
                FROM pillar_evaluations pe
                JOIN pillars p     ON p.id = pe.pillar_id
                JOIN submissions s ON s.id = pe.submission_id
                WHERE s.user_id = :memberId
                  AND s.id = (SELECT s2.id FROM submissions s2
                              WHERE s2.user_id = :memberId
                                AND EXISTS (SELECT 1 FROM pillar_evaluations pe2
                                            WHERE pe2.submission_id = s2.id)
                              ORDER BY s2.submitted_at DESC NULLS LAST, s2.created_at DESC
                              LIMIT 1)
                ORDER BY p.display_order, p.name
                """,
                new MapSqlParameterSource("memberId", memberId),
                (rs, i) -> new PillarRow(rs.getString("name"), rs.getBigDecimal("score_percentage"),
                        rs.getString("maturity_label"), instant(rs, "evaluated_at")));
    }

    public record NoteRow(UUID id, UUID coachId, String coachName, String body,
                          Instant createdAt, Instant updatedAt) {}

    /** All coach notes about this member, newest first, labeled with the coach's name. */
    public List<NoteRow> notes(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT n.id, n.coach_id, cu.name AS coach_name, n.body, n.created_at, n.updated_at
                FROM coach_notes n
                LEFT JOIN users cu ON cu.id = n.coach_id
                WHERE n.org_id = :orgId AND n.member_id = :memberId
                ORDER BY n.created_at DESC
                """,
                params(orgId, memberId),
                (rs, i) -> new NoteRow(rs.getObject("id", UUID.class),
                        rs.getObject("coach_id", UUID.class), rs.getString("coach_name"),
                        rs.getString("body"), instant(rs, "created_at"), instant(rs, "updated_at")));
    }

    public record AnnouncementRow(UUID id, String cohortName, String authorName, String body,
                                  Instant createdAt) {}

    /** Announcements the member received: cohort-scoped posts for cohorts they belong to. */
    public List<AnnouncementRow> announcements(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT a.id, c.name AS cohort_name, au.name AS author_name, a.body, a.created_at
                FROM announcements a
                JOIN cohorts c ON c.id = a.cohort_id AND c.org_id = :orgId
                JOIN cohort_members cm ON cm.cohort_id = a.cohort_id AND cm.user_id = :memberId
                LEFT JOIN users au ON au.id = a.author_id
                WHERE a.org_id = :orgId
                ORDER BY a.created_at DESC
                LIMIT 50
                """,
                params(orgId, memberId),
                (rs, i) -> new AnnouncementRow(rs.getObject("id", UUID.class),
                        rs.getString("cohort_name"), rs.getString("author_name"),
                        rs.getString("body"), instant(rs, "created_at")));
    }

    /* ---------------------------------------------------------------- helpers */

    private static MapSqlParameterSource params(UUID orgId, UUID memberId) {
        return new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
