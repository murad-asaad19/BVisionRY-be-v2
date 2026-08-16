package com.bvisionry.cohortview.repository;

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

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.programaccess.CohortInstruments;
import com.bvisionry.common.programaccess.Learner;
import com.bvisionry.common.programaccess.MemberActivity;
import com.bvisionry.common.programaccess.ProgramAudience;
import com.bvisionry.common.programaccess.TaskCompletion;
import com.bvisionry.common.progress.CourseProgressSql;

/**
 * Cross-feature reads for the dedicated cohort view (redesign spec §13.7), raw
 * SQL through {@link NamedParameterJdbcTemplate} — the same stance as
 * {@code founderprofile.FounderProfileReadRepository}: the ArchUnit ratchet
 * forbids new feature→feature imports, so this class depends on the schema and
 * imports no other feature's types. The shared predicates it does import all
 * live in {@code common} for exactly that reason.
 *
 * <p><strong>The org slice is the unit (§13.7).</strong> A cohort spans orgs
 * since V171, so "the roster" here always means
 * {@code cohort_members × users(organization_id = :orgId, Learner.ROLE_IN)} —
 * never the cohort's whole membership. Every count, every activity row and
 * every milestone denominator in this class goes through that join; a number
 * that skipped it would quote another org's founders back to this one.
 */
@Repository
public class CohortViewReadRepository {

    /** The org slice of a cohort's roster: this org's MEMBER rows, and only those. */
    private static final String ORG_SLICE = """
            JOIN users u ON u.id = cm.user_id
                        AND u.organization_id = :orgId
                        AND u.status = 'ACTIVE'
                        AND\s""" + Learner.ROLE_PREDICATE.formatted("u");

    /**
     * Scalar sub-select: the member's Nth-latest (0 = latest, 1 = previous)
     * evaluated overall score ON THIS COHORT'S OWN INSTRUMENTS
     * ({@link CohortInstruments} — cohort surfaces never quote an unrelated
     * instrument's score; a member with no sitting on them reads NULL).
     * {@code :cohortId} must be bound by the composing query.
     */
    private static String friOnCohortInstruments(String userColumn, int offset) {
        return """
                (SELECT cos.overall_score_percentage
                   FROM submissions cs
                   JOIN overall_summaries cos ON cos.submission_id = cs.id
                  WHERE cs.user_id = %s AND cs.status = 'EVALUATED'
                    AND %s
                  ORDER BY cs.evaluated_at DESC NULLS LAST, cs.created_at DESC
                  OFFSET %d LIMIT 1)""".formatted(userColumn,
                CohortInstruments.ON_COHORT_INSTRUMENT.formatted("cs", ":cohortId"), offset);
    }

    private final NamedParameterJdbcTemplate jdbc;

    public CohortViewReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* -------------------------------------------------------------- overview */

    public record CohortRow(UUID id, String name, String description, String status,
                            Instant createdAt, LocalDate startAt, LocalDate endAt,
                            String baselinePipelineName, String distancePipelineName) {}

    /**
     * The cohort's header, org-scoped through {@code cohort_orgs} — empty means
     * "not assigned to your org" → 404.
     *
     * <p>Schema note: the cohort itself carries no schedule. {@code startAt} is
     * the lifecycle instant {@code cohorts.launched_at} (the FIRST launch,
     * kept across unlaunch; null only if never launched) and
     * {@code endAt} is {@code program_settings.end_at}, which has been keyed per
     * cohort since V122 — those are the only two dates the schema records.
     *
     * <p>{@code baselinePipelineName}/{@code distancePipelineName} are the
     * header's measurement-pair chip (redesign spec §5): the names behind
     * {@code program_settings.baseline_pipeline_id}/{@code distance_pipeline_id}
     * (set on the Curriculum tab's Distance card), both null until an admin
     * designates the pair — same "no pending state" guard the comparison
     * feature uses.
     */
    public Optional<CohortRow> cohort(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT c.id, c.name, c.description, c.status, c.created_at,
                       c.launched_at::date AS start_at, ps.end_at::date AS end_at,
                       bp.name AS baseline_pipeline_name, dp.name AS distance_pipeline_name
                FROM cohorts c
                LEFT JOIN program_settings ps ON ps.cohort_id = c.id
                LEFT JOIN pipelines bp ON bp.id = ps.baseline_pipeline_id
                LEFT JOIN pipelines dp ON dp.id = ps.distance_pipeline_id
                WHERE c.id = :cohortId
                  AND EXISTS (SELECT 1 FROM cohort_orgs co
                              WHERE co.cohort_id = c.id AND co.org_id = :orgId)
                """,
                params(orgId, cohortId),
                (rs, i) -> new CohortRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("description"), rs.getString("status"),
                        instant(rs, "created_at"), rs.getObject("start_at", LocalDate.class),
                        rs.getObject("end_at", LocalDate.class),
                        rs.getString("baseline_pipeline_name"),
                        rs.getString("distance_pipeline_name")))
                .stream().findFirst();
    }

    public record CoachRow(UUID id, String name, boolean orgWide) {}

    /**
     * The coaches on this cohort: whole-cohort grants on it, plus the org's
     * org-wide grants (V176). {@code bool_and} makes {@code orgWide} true only
     * when EVERY grant the coach holds here is the org-wide one — hold a cohort
     * grant as well and you are this cohort's coach, not the house coach.
     *
     * <p>The COACH's own row is this query's to pin — org, role, ACTIVE. A grant
     * asserts nothing about the coach it names (see
     * {@code common.coachaccess.CoachAccess#VISIBLE_COACH_PREDICATE}'s javadoc,
     * and the identical three lines in {@code coaching}'s
     * {@code coachesOfMember}): without them a suspended coach, one demoted out
     * of the role while a stale grant survives, or another tenant's coach named
     * by a hand-written grant would still be chipped onto this header.
     */
    public List<CoachRow> coaches(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT u.id, u.name, bool_and(ca.cohort_id IS NULL) AS org_wide
                FROM coach_assignments ca
                JOIN users u ON u.id = ca.coach_id
                WHERE ca.org_id = :orgId
                  AND u.organization_id = :orgId
                  AND u.role = 'COACH'
                  AND u.status = 'ACTIVE'
                  AND (ca.cohort_id = :cohortId
                       OR (ca.cohort_id IS NULL AND ca.member_id IS NULL))
                GROUP BY u.id, u.name
                ORDER BY u.name
                """,
                params(orgId, cohortId),
                (rs, i) -> new CoachRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("org_wide")));
    }

    public record MilestoneRow(UUID taskId, String name, String role, LocalDate dueDate,
                               int doneCount, int totalMembers) {}

    /**
     * The cohort's LIVE milestone tasks (V164 {@code milestone_role}) in board
     * order, each with the org slice's done / total.
     *
     * <p>The audience predicate sits in the roster JOIN, not in the WHERE: a
     * member the module never reached is dropped from BOTH sides of the
     * fraction, so a targeted module cannot deflate its own percentage.
     * {@code count(u.id)} skips the null row a milestone with an empty audience
     * leaves behind.
     */
    public List<MilestoneRow> milestones(UUID orgId, UUID cohortId) {
        return jdbc.query(("""
                SELECT t.id, t.name, t.milestone_role, t.due_date,
                       count(u.id) AS total_members,
                       count(u.id) FILTER (WHERE %1$s) AS done_count
                FROM program_modules m
                JOIN program_tasks t ON t.module_id = m.id
                                    AND t.status = 'LIVE'
                                    AND t.milestone_role IS NOT NULL
                LEFT JOIN cohort_members cm ON cm.cohort_id = m.cohort_id
                LEFT JOIN users u ON u.id = cm.user_id
                                 AND u.organization_id = :orgId
                                 AND u.status = 'ACTIVE'
                                 AND u.""" + Learner.ROLE_IN + """

                                 AND %2$s
                WHERE m.cohort_id = :cohortId
                GROUP BY t.id, t.name, t.milestone_role, t.due_date, m.position, t.position
                ORDER BY m.position, t.position
                """).formatted(TaskCompletion.DONE_FOR_USER.formatted("u.id"),
                        ProgramAudience.INCLUDES_USER.formatted("u.id")),
                params(orgId, cohortId),
                (rs, i) -> new MilestoneRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("milestone_role"), rs.getObject("due_date", LocalDate.class),
                        rs.getInt("done_count"), rs.getInt("total_members")));
    }

    public record OutlineModuleRow(UUID moduleId, String name, String summary, String pillarLabel,
                                   int position, String lockMode, Instant unlockAt,
                                   String audienceMode, int audienceCount) {}

    /**
     * The cohort's modules in board order with the org slice they reach. This is
     * the READ twin of the builder's board ({@code ProgramAdminController},
     * super-admin only): an org admin running the cohort sees the curriculum and
     * its reach, never a way to author it.
     *
     * <p>{@code audienceCount} counts through {@link ProgramAudience}, so a
     * module narrowed to named members reports how many of THIS org's founders
     * it actually reaches — the honest denominator for its task rows below.
     */
    public List<OutlineModuleRow> outlineModules(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT m.id, m.name, m.summary, m.pillar_label, m.position,
                       m.lock_mode, m.unlock_at, m.assign_mode,
                       (SELECT count(*)
                          FROM cohort_members cm
                          %2$s
                         WHERE cm.cohort_id = m.cohort_id AND %1$s) AS audience_count
                FROM program_modules m
                WHERE m.cohort_id = :cohortId
                ORDER BY m.position
                """.formatted(ProgramAudience.INCLUDES_USER.formatted("u.id"), ORG_SLICE),
                params(orgId, cohortId),
                (rs, i) -> new OutlineModuleRow(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("summary"),
                        rs.getString("pillar_label"), rs.getInt("position"),
                        rs.getString("lock_mode"), instant(rs, "unlock_at"),
                        rs.getString("assign_mode"), rs.getInt("audience_count")));
    }

    public record OutlineTaskRow(UUID moduleId, UUID taskId, String name, String taskType,
                                 String milestoneRole, LocalDate dueDate, int position,
                                 int doneCount, int totalMembers) {}

    /**
     * Every LIVE task of the cohort with the org slice's done / total — the same
     * shape and the same authority as {@link #milestones}, widened to the whole
     * curriculum. DRAFT tasks are the builder's business and never appear here.
     */
    public List<OutlineTaskRow> outlineTasks(UUID orgId, UUID cohortId) {
        return jdbc.query(("""
                SELECT m.id AS module_id, t.id, t.name, t.task_type, t.milestone_role,
                       t.due_date, t.position,
                       count(u.id) AS total_members,
                       count(u.id) FILTER (WHERE %1$s) AS done_count
                FROM program_modules m
                JOIN program_tasks t ON t.module_id = m.id AND t.status = 'LIVE'
                LEFT JOIN cohort_members cm ON cm.cohort_id = m.cohort_id
                LEFT JOIN users u ON u.id = cm.user_id
                                 AND u.organization_id = :orgId
                                 AND u.status = 'ACTIVE'
                                 AND u.""" + Learner.ROLE_IN + """

                                 AND %2$s
                                 AND %3$s
                WHERE m.cohort_id = :cohortId
                GROUP BY m.id, m.position, t.id, t.name, t.task_type, t.milestone_role,
                         t.due_date, t.position
                ORDER BY m.position, t.position
                """).formatted(TaskCompletion.DONE_FOR_USER.formatted("u.id"),
                        ProgramAudience.INCLUDES_USER.formatted("u.id"),
                        // ProgramRules.gates in SQL: a COURSE task blocked for this
                        // org reads 0/0 here, so the outline's module aggregate
                        // matches the journey's fraction.
                        TaskCompletion.COUNTS_FOR_USER.formatted("u.id")),
                params(orgId, cohortId),
                (rs, i) -> new OutlineTaskRow(rs.getObject("module_id", UUID.class),
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("task_type"), rs.getString("milestone_role"),
                        rs.getObject("due_date", LocalDate.class), rs.getInt("position"),
                        rs.getInt("done_count"), rs.getInt("total_members")));
    }

    public record ActivityRow(String type, String memberName, String title, Instant at) {}

    /**
     * The org slice's 30 most recent events, newest first — one UNION ALL arm
     * per kind of "the member did something here". Every arm re-states the org
     * slice on its own {@code users} join and pins the event to this cohort's
     * tasks (or, for attendance, to this cohort's sessions), so no arm can
     * widen past either boundary.
     *
     * <p>SESSION_ATTENDED reads {@code session_attendance × sessions}, which is
     * cohort-scoped outright since V171 dropped the redundant {@code org_id}.
     * Its instant is the SESSION DATE, not {@code marked_at} — the tick is an
     * admin's bookkeeping, the attendance is the member's event — and a session
     * dated in the future is excluded rather than pinned to the top of a feed
     * of things that have happened.
     */
    public List<ActivityRow> activity(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT 'TASK_SUBMITTED' AS type, u.name AS member_name, t.name AS title,
                       ps.submitted_at AS happened_at
                FROM program_submissions ps
                JOIN program_tasks t   ON t.id = ps.task_id
                JOIN program_modules m ON m.id = t.module_id AND m.cohort_id = :cohortId
                JOIN cohort_members cm ON cm.cohort_id = m.cohort_id AND cm.user_id = ps.user_id
                %1$s
                WHERE ps.submitted_at IS NOT NULL

                UNION ALL
                SELECT 'EXERCISE_SUBMITTED', u.name, et.name, es.submitted_at
                FROM exercise_submissions es
                JOIN exercise_assignments ea ON ea.id = es.assignment_id
                JOIN exercise_templates et   ON et.id = ea.template_id
                JOIN program_tasks t         ON t.id = ea.program_task_id
                JOIN program_modules m       ON m.id = t.module_id AND m.cohort_id = :cohortId
                JOIN cohort_members cm       ON cm.cohort_id = m.cohort_id AND cm.user_id = es.user_id
                %1$s
                WHERE es.submitted_at IS NOT NULL

                UNION ALL
                SELECT 'ASSESSMENT_EVALUATED', u.name, p.name, s.evaluated_at
                FROM submissions s
                JOIN program_tasks t   ON t.id = s.program_task_id
                JOIN program_modules m ON m.id = t.module_id AND m.cohort_id = :cohortId
                JOIN assignments a     ON a.id = s.assignment_id
                JOIN pipelines p       ON p.id = a.pipeline_id
                JOIN cohort_members cm ON cm.cohort_id = m.cohort_id AND cm.user_id = s.user_id
                %1$s
                WHERE s.evaluated_at IS NOT NULL

                UNION ALL
                SELECT 'SESSION_ATTENDED', u.name, COALESCE(sn.title, sn.type), sn.session_date
                FROM session_attendance sa
                JOIN sessions sn       ON sn.id = sa.session_id AND sn.cohort_id = :cohortId
                JOIN cohort_members cm ON cm.cohort_id = sn.cohort_id AND cm.user_id = sa.member_id
                %1$s
                WHERE sn.session_date <= now()

                ORDER BY happened_at DESC
                LIMIT 30
                """.formatted(ORG_SLICE),
                params(orgId, cohortId),
                (rs, i) -> new ActivityRow(rs.getString("type"), rs.getString("member_name"),
                        rs.getString("title"), instant(rs, "happened_at")));
    }

    /* ---------------------------------------------------------------- roster */

    public record RosterRow(UUID userId, String name, String email, String memberType,
                            BigDecimal friLatest, BigDecimal friDelta, int progressDone,
                            int progressTotal, int overdueCount, int awaitingReview,
                            Instant lastActivityAt) {}

    /**
     * The org slice's roster with its per-member numbers, ordered by name. One
     * statement with correlated sub-selects: the row count is a cohort roster,
     * so clarity beats a pile of grouped joins.
     *
     * <p>{@code friLatest} is the member's latest evaluated sitting ON THIS
     * COHORT'S OWN INSTRUMENTS ({@link #friOnCohortInstruments} — never the
     * global latest, which may be an unrelated instrument), and
     * {@code friDelta} is that sitting minus the PREVIOUS evaluated sitting
     * within the same restricted set — null until there are two. A cohort with
     * no assessment tasks, or a member who has never sat one of its
     * instruments, reads null for both.
     *
     * <p>The three program counters share one shape: LIVE tasks of THIS cohort
     * whose module audience includes the member. {@code COALESCE(…, FALSE)}
     * guards the done verdict because {@link TaskCompletion} returns NULL for a
     * task type it has no arm for.
     *
     * <p>{@code awaitingReview} is the third needs-attention reason (redesign
     * spec §13.7 gap B): SUBMITTED exercise submissions on this cohort's LIVE
     * tasks — the same verdict {@code coaching.CoachingReadRepository}'s roster
     * uses, narrowed from the whole org to this cohort's tasks.
     */
    public List<RosterRow> roster(UUID orgId, UUID cohortId) {
        String audienceTasks = """
                SELECT count(*) FROM program_modules m
                          JOIN program_tasks t ON t.module_id = m.id AND t.status = 'LIVE'
                         WHERE m.cohort_id = :cohortId AND %s AND %s""".formatted(
                        ProgramAudience.INCLUDES_USER.formatted("u.id"),
                        TaskCompletion.COUNTS_FOR_USER.formatted("u.id"));
        String done = "COALESCE(" + TaskCompletion.DONE_FOR_USER.formatted("u.id") + ", FALSE)";
        String awaitingReview = """
                SELECT count(*) FROM program_modules m
                          JOIN program_tasks t ON t.module_id = m.id AND t.status = 'LIVE'
                          JOIN exercise_assignments ea ON ea.program_task_id = t.id
                          JOIN exercise_submissions es ON es.assignment_id = ea.id
                         WHERE m.cohort_id = :cohortId AND es.user_id = u.id
                           AND es.status = 'SUBMITTED'""";

        return jdbc.query("""
                SELECT u.id, u.name, u.email, u.user_type,
                       %6$s AS fri_latest,
                       %7$s AS fri_previous,
                       (%1$s) AS progress_total,
                       (%1$s AND %2$s) AS progress_done,
                       (%1$s AND t.due_date < CURRENT_DATE AND NOT %2$s) AS overdue_count,
                       (%5$s) AS awaiting_review,
                       %3$s AS last_activity_at
                FROM cohort_members cm
                %4$s
                WHERE cm.cohort_id = :cohortId
                ORDER BY u.name, u.email
                """.formatted(audienceTasks, done, MemberActivity.LAST_ACTIVITY.formatted("u"),
                        ORG_SLICE, awaitingReview, friOnCohortInstruments("u.id", 0),
                        friOnCohortInstruments("u.id", 1)),
                params(orgId, cohortId),
                (rs, i) -> new RosterRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getString("user_type"),
                        rs.getBigDecimal("fri_latest"),
                        delta(rs.getBigDecimal("fri_latest"), rs.getBigDecimal("fri_previous")),
                        rs.getInt("progress_done"), rs.getInt("progress_total"),
                        rs.getInt("overdue_count"), rs.getInt("awaiting_review"),
                        instant(rs, "last_activity_at")));
    }

    /* -------------------------------------------------- member readiness */

    public record ReadinessRow(UUID submissionId, BigDecimal friLatest, BigDecimal friDelta,
                               Instant evaluatedAt) {}

    /**
     * The member's readiness AS THIS COHORT MEASURES IT: their latest evaluated
     * sitting on the cohort's own instruments ({@link #friOnCohortInstruments}),
     * with the delta against the previous such sitting. Empty when the cohort
     * has no assessment tasks or the member never sat one of its instruments —
     * the member-in-cohort card renders that as "—", never the global latest.
     */
    public Optional<ReadinessRow> readiness(UUID cohortId, UUID memberId) {
        return jdbc.query("""
                SELECT s.id, os.overall_score_percentage AS fri_latest, s.evaluated_at,
                       %2$s AS fri_previous
                FROM submissions s
                JOIN overall_summaries os ON os.submission_id = s.id
                WHERE s.user_id = :memberId AND s.status = 'EVALUATED'
                  AND %1$s
                ORDER BY s.evaluated_at DESC NULLS LAST, s.created_at DESC
                LIMIT 1
                """.formatted(CohortInstruments.ON_COHORT_INSTRUMENT.formatted("s", ":cohortId"),
                        friOnCohortInstruments(":memberId", 1)),
                new MapSqlParameterSource("cohortId", cohortId).addValue("memberId", memberId),
                (rs, i) -> new ReadinessRow(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("fri_latest"),
                        delta(rs.getBigDecimal("fri_latest"), rs.getBigDecimal("fri_previous")),
                        instant(rs, "evaluated_at")))
                .stream().findFirst();
    }

    public record ReadinessPillarRow(String pillarName, BigDecimal scorePercentage,
                                     String maturityLabel) {}

    /** The pillar rows of one sitting, in authoring order — the Growth tab's table. */
    public List<ReadinessPillarRow> pillarScores(UUID submissionId) {
        return jdbc.query("""
                SELECT p.name, pe.score_percentage, pe.maturity_label
                FROM pillar_evaluations pe
                JOIN pillars p ON p.id = pe.pillar_id
                WHERE pe.submission_id = :submissionId
                ORDER BY p.display_order, p.name
                """,
                new MapSqlParameterSource("submissionId", submissionId),
                (rs, i) -> new ReadinessPillarRow(rs.getString("name"),
                        rs.getBigDecimal("score_percentage"), rs.getString("maturity_label")));
    }

    /* ------------------------------------------------------- course progress */


    public record CourseRow(UUID courseId, String title, String status, Integer progressPct,
                            Instant enrolledAt, Instant completedAt, boolean certificateIssued) {}

    /**
     * The course header for one member. LEFT JOIN on the enrolment so an
     * un-enrolled member still gets the course: with no row,
     * {@link CourseProgressSql#LIVE_PROGRESS_PCT} evaluates to NULL of its own
     * accord (its lesson count correlates on {@code e.course_id}), which is
     * exactly the "not started" the DTO promises.
     *
     * <p>Visibility, not just existence, is the 404 boundary: a course of
     * another org is absent unless the member is actually enrolled in it —
     * an enrolment is proof they could already open it, so revoking visibility
     * later must not blank an in-flight learner's screen.
     */
    public Optional<CourseRow> course(UUID orgId, UUID memberId, UUID courseId) {
        return jdbc.query("""
                SELECT c.id, c.title, e.status, %1$s AS progress_pct,
                       e.enrolled_at, e.completed_at,
                       EXISTS (SELECT 1 FROM certificate cert
                               WHERE cert.user_id = :memberId AND cert.course_id = c.id)
                           AS certificate_issued
                FROM course c
                LEFT JOIN enrollment e ON e.course_id = c.id AND e.user_id = :memberId
                WHERE c.id = :courseId AND (e.id IS NOT NULL OR %2$s)
                """.formatted(CourseProgressSql.LIVE_PROGRESS_PCT,
                        CourseVisibilityAccess.VISIBLE_TO_ORG.formatted(":orgId")),
                new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId)
                        .addValue("courseId", courseId),
                (rs, i) -> new CourseRow(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("status"), (Integer) rs.getObject("progress_pct"),
                        instant(rs, "enrolled_at"), instant(rs, "completed_at"),
                        rs.getBoolean("certificate_issued")))
                .stream().findFirst();
    }

    public record LessonRow(UUID sectionId, String sectionTitle, int sectionPosition,
                            UUID contentId, String contentTitle, int contentPosition,
                            boolean completed, Instant completedAt) {}

    /**
     * The course's sections and lessons in authoring order, each carrying this
     * member's completion. A section with no lessons yields one row with a null
     * {@code contentId}; the service turns that into an empty lesson list.
     * Position is the schema's {@code sequence} column on both tables.
     */
    public List<LessonRow> lessons(UUID memberId, UUID courseId) {
        return jdbc.query("""
                SELECT s.id AS section_id, s.title AS section_title, s.sequence AS section_position,
                       ct.id AS content_id, ct.title AS content_title,
                       ct.sequence AS content_position,
                       COALESCE(cp.completed, FALSE) AS completed, cp.completed_at
                FROM section s
                LEFT JOIN content ct ON ct.section_id = s.id
                LEFT JOIN content_progress cp
                       ON cp.content_id = ct.id
                      AND cp.enrollment_id = (SELECT e.id FROM enrollment e
                                               WHERE e.user_id = :memberId
                                                 AND e.course_id = :courseId)
                WHERE s.course_id = :courseId
                ORDER BY s.sequence, s.title, ct.sequence, ct.title
                """,
                new MapSqlParameterSource("memberId", memberId).addValue("courseId", courseId),
                (rs, i) -> new LessonRow(rs.getObject("section_id", UUID.class),
                        rs.getString("section_title"), rs.getInt("section_position"),
                        rs.getObject("content_id", UUID.class), rs.getString("content_title"),
                        rs.getInt("content_position"), rs.getBoolean("completed"),
                        instant(rs, "completed_at")));
    }

    /* --------------------------------------------------------------- helpers */

    /** Latest minus previous; null unless both sittings exist. */
    private static BigDecimal delta(BigDecimal latest, BigDecimal previous) {
        return latest == null || previous == null ? null : latest.subtract(previous);
    }

    private static MapSqlParameterSource params(UUID orgId, UUID cohortId) {
        return new MapSqlParameterSource("orgId", orgId).addValue("cohortId", cohortId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
