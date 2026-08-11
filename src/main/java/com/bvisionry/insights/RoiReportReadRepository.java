package com.bvisionry.insights;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.programaccess.ProgramAudience;
import com.bvisionry.common.programaccess.TaskCompletion;

/**
 * ROI reporting reads (roadmap §7 item 16): one cohort's pillar movement from
 * intake to latest, per-founder deltas and module completion, as raw SQL
 * through {@link NamedParameterJdbcTemplate}.
 *
 * <p><strong>Why raw SQL rather than the owning features' repositories.</strong>
 * The report reads pillar evaluations ({@code assessment}/{@code evaluation}),
 * cohort membership and program modules ({@code programflow}); the ArchUnit
 * ratchet ({@code noCrossFeatureDependencies}) forbids new feature→feature
 * imports, so — following the sibling {@link BenchmarkReadRepository} — this
 * class depends on the schema instead and imports no other feature's types.
 *
 * <p><strong>Tenant scoping.</strong> This is ORG-INTERNAL data, and it is
 * scoped on all THREE of its axes: {@code c.org_id = :orgId} on the cohort,
 * {@code a.organization_id = :orgId} on the assessment context (the pair the
 * benchmark's cohort segment uses, so the two insight surfaces can never
 * disagree about which founders belong to a cohort) and
 * {@code u.organization_id = :orgId} on the roster itself. The third is not
 * redundant: nothing clears {@code cohort_members} when a member is moved
 * between orgs or removed and anonymised, so a membership row can outlive the
 * membership it records. With all three, there is no cross-tenant read
 * anywhere in this class.
 *
 * <h2>The grain: "intake" vs "latest"</h2>
 * An <em>evaluated submission</em> is a submission on the requested pipeline,
 * inside the caller's org, whose status is {@code EVALUATED} (see
 * {@link #EVALUATED} — a quarantined {@code NEEDS_REVIEW} run is not a
 * measurement). For each founder in the cohort:
 * <ul>
 *   <li><strong>intake</strong> = their FIRST evaluated submission,</li>
 *   <li><strong>latest</strong> = their LAST evaluated submission,</li>
 * </ul>
 * ordered by {@code COALESCE(submitted_at, created_at)} with the submission id
 * as a deterministic tiebreak. With three assessments the comparison is #1 vs
 * #3 — never the middle one. A founder with exactly ONE evaluated submission
 * has no second point, so their latest is deliberately NULL: the report shows
 * them intake-only rather than inventing a zero delta.
 *
 * <p><strong>Cohort averages are PAIRED.</strong>
 * {@link #pillarMovement} averages intake and latest over the same founders —
 * those with at least two evaluated submissions AND an evaluation of that
 * pillar on both ends. Averaging a changing population would make the delta an
 * artefact of who happened to be measured twice; the paired count is returned
 * so the report can name exactly how many founders the movement describes.
 *
 * <p><strong>Completion</strong> follows the coach console's grain: LIVE tasks
 * of the cohort's modules whose audience includes the founder, with per-type
 * done state from the shared {@link TaskCompletion} fragment
 * ({@code programflow.web.ProgramRules} semantics) — so this report, the coach
 * console and the engagement record quote the same completion numbers.
 *
 * <p>ponytail: live aggregation, no materialised table — a cohort is tens of
 * founders and the scan is bounded by the cohort, not the platform. Add a
 * rollup only if a cohort ever runs into the thousands.
 */
@Repository
public class RoiReportReadRepository {

    /** Names for the report head — resolved from the DB, never from the caller. */
    public record ReportHeader(String organizationName, String programName, String assessmentName) {}

    /** One pillar of the pipeline — the axis every movement row hangs on. */
    public record PillarRef(UUID id, String name) {}

    /** Paired intake/latest averages for one pillar over {@code pairedFounders} founders. */
    public record PillarMovementRow(UUID pillarId, int pairedFounders,
                                    BigDecimal intakeAverage, BigDecimal latestAverage) {}

    /**
     * One cohort member. {@code latestOn}/{@code latestScore} are null for a
     * founder with fewer than two evaluated submissions — no fabricated delta.
     * Scores are the submission's canonical overall score (see
     * {@link #founders}).
     */
    public record FounderRow(String founderName, int assessments,
                             LocalDate intakeOn, BigDecimal intakeScore,
                             LocalDate latestOn, BigDecimal latestScore,
                             int tasksAssigned, int tasksCompleted) {}

    /**
     * Every evaluated submission of every founder in the cohort, ranked from
     * both ends. {@code rn_first = 1} is intake, {@code rn_last = 1} is latest
     * and {@code taken} is how many the founder has — the whole intake/latest
     * grain lives here and nowhere else.
     *
     * <p><strong>"Evaluated" means {@code status = 'EVALUATED'}</strong>, the
     * same population every sibling surface reports on ({@code
     * TeamDashboardService}, the team insight exports, {@code
     * PlatformAnalyticsService}). The presence of {@code pillar_evaluations}
     * rows is NOT the test: a run that fails its repair retries still persists
     * partial rows — flagged {@code ai_failed}, scored {@code 0.00}, with a
     * {@code 0.00} overall summary written before the degraded check — under
     * {@code NEEDS_REVIEW} ("fail loud, not quiet",
     * {@link com.bvisionry.common.enums.SubmissionStatus}). Counting those as
     * an intake would print a quarantined zero as a real measurement and turn
     * the next clean assessment into a spectacular fabricated rise. The filter
     * also excludes {@code PENDING_REEDIT} — a submission mid-unlock is being
     * rewritten, and its superseded scores are not a measurement either.
     */
    private static final String EVALUATED = """
            evaluated AS (
                SELECT s.id, s.user_id,
                       COALESCE(s.submitted_at, s.created_at) AS taken_at,
                       row_number() OVER (PARTITION BY s.user_id
                           ORDER BY COALESCE(s.submitted_at, s.created_at), s.id) AS rn_first,
                       row_number() OVER (PARTITION BY s.user_id
                           ORDER BY COALESCE(s.submitted_at, s.created_at) DESC, s.id DESC) AS rn_last,
                       count(*) OVER (PARTITION BY s.user_id) AS taken
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                WHERE a.pipeline_id = :pipelineId
                  AND a.organization_id = :orgId
                  AND s.status = 'EVALUATED'
                  AND EXISTS (SELECT 1 FROM pillar_evaluations pe WHERE pe.submission_id = s.id)
                  AND EXISTS (SELECT 1 FROM cohort_members cm
                              JOIN cohorts c ON c.id = cm.cohort_id
                              WHERE cm.user_id = s.user_id
                                AND c.id = :cohortId
                                AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId))
            )""";

    private final NamedParameterJdbcTemplate jdbc;

    public RoiReportReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The report's names, or empty when the cohort is absent/foreign or the
     * pipeline is absent/unpublished. One lookup for both so an unpublished
     * pipeline reads exactly like a nonexistent one (the pillar axis must not
     * become an existence oracle for DRAFT pillar names) and the service has a
     * single 404 branch.
     */
    public Optional<ReportHeader> header(UUID orgId, UUID cohortId, UUID pipelineId) {
        return jdbc.query("""
                SELECT o.name AS org_name, c.name AS cohort_name, p.name AS pipeline_name
                FROM cohorts c
                JOIN cohort_orgs cox ON cox.cohort_id = c.id AND cox.org_id = :orgId
                JOIN organizations o ON o.id = cox.org_id
                JOIN pipelines p ON p.id = :pipelineId AND p.status = 'PUBLISHED'
                WHERE c.id = :cohortId
                """,
                params(orgId, cohortId, pipelineId),
                (rs, i) -> new ReportHeader(rs.getString("org_name"),
                        rs.getString("cohort_name"), rs.getString("pipeline_name")))
                .stream().findFirst();
    }

    /**
     * The pipeline's STANDARD pillars in display order — the report's movement
     * axis. The auto-created PERSONAL pillar ("General Information", V30) is
     * excluded: {@code EvaluationEngine} strips it before scoring, so it can
     * never hold a pillar evaluation and would otherwise render as a permanent
     * "not yet measured twice" first row in every funder document.
     */
    public List<PillarRef> pillars(UUID pipelineId) {
        return jdbc.query("""
                SELECT id, name FROM pillars
                WHERE pipeline_id = :pipelineId
                  AND type <> 'PERSONAL'
                ORDER BY display_order, name
                """,
                new MapSqlParameterSource("pipelineId", pipelineId),
                (rs, i) -> new PillarRef(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    /**
     * Paired intake vs latest averages per pillar. The self-join pins the SAME
     * founder's first and last evaluated submission together and the inner join
     * on {@code pillar_id} keeps a founder in a pillar's average only when both
     * ends measured that pillar — so the delta is always a like-for-like move,
     * never a population artefact. Pillars nobody was measured twice on are
     * simply absent (→ null averages upstream).
     */
    public List<PillarMovementRow> pillarMovement(UUID orgId, UUID cohortId, UUID pipelineId) {
        return jdbc.query("WITH " + EVALUATED + """
                SELECT intake.pillar_id,
                       count(*) AS paired,
                       round(avg(intake.score_percentage), 1) AS intake_avg,
                       round(avg(latest.score_percentage), 1) AS latest_avg
                FROM evaluated f
                JOIN evaluated l ON l.user_id = f.user_id AND l.rn_last = 1
                JOIN pillar_evaluations intake ON intake.submission_id = f.id
                JOIN pillar_evaluations latest ON latest.submission_id = l.id
                                              AND latest.pillar_id = intake.pillar_id
                WHERE f.rn_first = 1 AND f.taken >= 2
                GROUP BY intake.pillar_id
                """,
                params(orgId, cohortId, pipelineId),
                (rs, i) -> new PillarMovementRow(rs.getObject("pillar_id", UUID.class),
                        rs.getInt("paired"), rs.getBigDecimal("intake_avg"),
                        rs.getBigDecimal("latest_avg")));
    }

    /**
     * Every member of the cohort with their intake/latest overall scores and
     * their module completion counts.
     *
     * <p><strong>The overall score is the platform's canonical one</strong> —
     * {@code overall_summaries.overall_score_percentage}, the same number
     * {@code MemberResultsService}, {@code TeamDashboardService}, the team
     * insights exports and {@code PublicAssessmentService} show. An unweighted
     * pillar mean computed here would disagree with the founder's OWN results
     * PDF for the identical submission, which is exactly the contradiction a
     * funder should never be handed. There is deliberately NO fallback to a
     * pillar mean: {@code saveOverallSummary} runs on every evaluation before
     * the degraded check, so an {@code EVALUATED} submission always has this
     * row, and mixing a weighted overall with an unweighted mean across the two
     * ends of a delta would subtract incommensurable numbers. A row missing it
     * reads as no score rather than as a different kind of score.
     *
     * <p>Cohort membership is the roster, but only for members of THIS org: a
     * {@code cohort_members} row outlives both a super-admin org move and the
     * member-removal anonymisation (neither clears it), so without
     * {@code u.organization_id = :orgId} a user who now belongs to another
     * tenant — or to none — would appear in a funder-facing roster under their
     * live name, and inflate {@code cohortSize} and the completion denominator
     * as a permanent zero-assessment ghost. Within the org, an enrolled founder
     * belongs in the headcount whether or not they were ever assessed, so the
     * cohort size, the completion rate and the measured counts all come from
     * this one query.
     *
     * <p>ponytail: unbounded — a cohort is tens of founders; paginate the day
     * one runs into the thousands, which is also the day the PDF stops being a
     * document.
     */
    public List<FounderRow> founders(UUID orgId, UUID cohortId, UUID pipelineId) {
        return jdbc.query("WITH " + EVALUATED + """
                ,
                summary AS (
                    SELECT e.user_id,
                           max(e.taken) AS taken,
                           max(e.taken_at) FILTER (WHERE e.rn_first = 1) AS intake_at,
                           max(os.overall_score_percentage) FILTER (WHERE e.rn_first = 1) AS intake_score,
                           max(e.taken_at) FILTER (WHERE e.rn_last = 1 AND e.taken >= 2) AS latest_at,
                           max(os.overall_score_percentage) FILTER (WHERE e.rn_last = 1 AND e.taken >= 2) AS latest_score
                    FROM evaluated e
                    LEFT JOIN overall_summaries os ON os.submission_id = e.id
                    WHERE e.rn_first = 1 OR e.rn_last = 1
                    GROUP BY e.user_id
                )
                SELECT u.name,
                       COALESCE(sm.taken, 0)          AS taken,
                       CAST(sm.intake_at AT TIME ZONE 'UTC' AS date) AS intake_on,
                       round(sm.intake_score, 1)      AS intake_score,
                       CAST(sm.latest_at AT TIME ZONE 'UTC' AS date) AS latest_on,
                       round(sm.latest_score, 1)      AS latest_score,
                       COALESCE(pr.total, 0)          AS tasks_assigned,
                       COALESCE(pr.done, 0)           AS tasks_completed
                FROM cohort_members cm
                JOIN cohorts c ON c.id = cm.cohort_id
                               AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId)
                JOIN users u   ON u.id = cm.user_id AND u.organization_id = :orgId
                LEFT JOIN summary sm ON sm.user_id = u.id
                LEFT JOIN LATERAL (
                    SELECT count(*)                   AS total,
                           count(*) FILTER (WHERE %s) AS done
                    FROM program_modules m
                    JOIN program_tasks t ON t.module_id = m.id AND t.status = 'LIVE'
                    WHERE m.cohort_id = c.id AND %s
                ) pr ON true
                WHERE c.id = :cohortId
                ORDER BY u.name, u.id
                """.formatted(TaskCompletion.DONE_FOR_USER.formatted("u.id"),
                        ProgramAudience.INCLUDES_USER.formatted("u.id")),
                params(orgId, cohortId, pipelineId),
                (rs, i) -> new FounderRow(rs.getString("name"), rs.getInt("taken"),
                        rs.getObject("intake_on", LocalDate.class), rs.getBigDecimal("intake_score"),
                        rs.getObject("latest_on", LocalDate.class), rs.getBigDecimal("latest_score"),
                        rs.getInt("tasks_assigned"), rs.getInt("tasks_completed")));
    }

    private static MapSqlParameterSource params(UUID orgId, UUID cohortId, UUID pipelineId) {
        return new MapSqlParameterSource("orgId", orgId)
                .addValue("cohortId", cohortId)
                .addValue("pipelineId", pipelineId);
    }
}
