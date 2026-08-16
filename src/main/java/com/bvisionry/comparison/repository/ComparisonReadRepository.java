package com.bvisionry.comparison.repository;

import com.bvisionry.common.programaccess.CohortInstruments;
import com.bvisionry.common.programaccess.CohortVisibility;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cross-feature reads for the comparison slice, expressed as raw SQL — the
 * ArchUnit ratchet forbids new feature→feature imports, so (like
 * {@code coaching.repository.CoachingReadRepository}) this class depends on
 * the schema: submissions/assignments ({@code assessment}), pillar
 * evaluations + overall summaries ({@code evaluation}), pipelines/pillars
 * ({@code pipeline}), cohorts + program settings ({@code programflow}) and the
 * shift-bands document ({@code platform_settings}).
 */
@Repository
public class ComparisonReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ComparisonReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------------------------------------------------- pair designation */

    public record PairCohortRow(UUID cohortId, String cohortName,
                                UUID baselinePipelineId, UUID distancePipelineId) {}

    private static final String PAIR_SELECT = """
            SELECT c.id AS cohort_id, c.name AS cohort_name,
                   ps.baseline_pipeline_id, ps.distance_pipeline_id
            FROM program_settings ps
            JOIN cohorts c ON c.id = ps.cohort_id
            """;

    /** The cohort's designated pair — empty when either side is undesignated. */
    public Optional<PairCohortRow> designatedPair(UUID cohortId) {
        return jdbc.query(PAIR_SELECT + """
                WHERE ps.cohort_id = :cohortId
                  AND ps.baseline_pipeline_id IS NOT NULL
                  AND ps.distance_pipeline_id IS NOT NULL
                """,
                new MapSqlParameterSource("cohortId", cohortId), this::pairRow)
                .stream().findFirst();
    }

    /**
     * Every cohort the user belongs to whose fully-designated pair names
     * {@code pipelineId} on EITHER side — the compute trigger fans out from an
     * evaluated submission through this.
     */
    public List<PairCohortRow> pairsInvolving(UUID userId, UUID pipelineId) {
        return jdbc.query(PAIR_SELECT + """
                JOIN cohort_members cm ON cm.cohort_id = c.id AND cm.user_id = :userId
                WHERE ps.baseline_pipeline_id IS NOT NULL
                  AND ps.distance_pipeline_id IS NOT NULL
                  AND (ps.baseline_pipeline_id = :pipelineId
                       OR ps.distance_pipeline_id = :pipelineId)
                """,
                new MapSqlParameterSource("userId", userId).addValue("pipelineId", pipelineId),
                this::pairRow);
    }

    /**
     * The member's cohort with a fully-designated pair, newest first — the
     * anchor for the member/coach growth read. Member-visible cohorts only
     * (LAUNCHED, V183) — a configured DRAFT must not become the
     * member's growth anchor before launch; staff-facing joins stay
     * unfiltered. ponytail: a member in two
     * designated cohorts sees the newest; per-cohort selection can come with
     * the cohort switcher if product asks.
     */
    public Optional<PairCohortRow> memberPairCohort(UUID userId) {
        return jdbc.query(PAIR_SELECT + """
                JOIN cohort_members cm ON cm.cohort_id = c.id AND cm.user_id = :userId
                WHERE ps.baseline_pipeline_id IS NOT NULL
                  AND ps.distance_pipeline_id IS NOT NULL
                  AND %s
                ORDER BY c.created_at DESC
                LIMIT 1
                """.formatted(CohortVisibility.MEMBER_VISIBLE.formatted("c")),
                new MapSqlParameterSource("userId", userId), this::pairRow)
                .stream().findFirst();
    }

    private PairCohortRow pairRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PairCohortRow(rs.getObject("cohort_id", UUID.class),
                rs.getString("cohort_name"),
                rs.getObject("baseline_pipeline_id", UUID.class),
                rs.getObject("distance_pipeline_id", UUID.class));
    }

    /* ------------------------------------------------- cohorts and tenancy */


    /** The user's own org — comparisons are stamped with the MEMBER's org (spec §13). */
    public Optional<UUID> userOrg(UUID userId) {
        return jdbc.query("SELECT organization_id FROM users WHERE id = :userId",
                new MapSqlParameterSource("userId", userId),
                (rs, i) -> rs.getObject("organization_id", UUID.class))
                .stream().findFirst();
    }

    public List<UUID> cohortMembers(UUID cohortId) {
        return jdbc.query("SELECT user_id FROM cohort_members WHERE cohort_id = :cohortId",
                new MapSqlParameterSource("cohortId", cohortId),
                (rs, i) -> rs.getObject("user_id", UUID.class));
    }

    /**
     * A platform cohort (spec §13) spans orgs; this is the tenant slice an org
     * admin may see and act on — cohort members whose org is {@code orgId}.
     * Comparisons are stamped with the member's org, so this is the same
     * boundary the org-scoped comparison reads use.
     */
    public List<UUID> cohortMembersInOrg(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT cm.user_id FROM cohort_members cm
                JOIN users u ON u.id = cm.user_id
                WHERE cm.cohort_id = :cohortId AND u.organization_id = :orgId
                """,
                new MapSqlParameterSource("cohortId", cohortId).addValue("orgId", orgId),
                (rs, i) -> rs.getObject("user_id", UUID.class));
    }


    public Map<UUID, String> userNames(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT id, name FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                (rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /* -------------------------------------------------- pipelines + pillars */

    public record PillarRow(UUID id, String name, int displayOrder) {}

    public List<PillarRow> pillarsOf(UUID pipelineId) {
        return jdbc.query("""
                SELECT id, name, display_order FROM pillars
                WHERE pipeline_id = :pipelineId
                ORDER BY display_order, name
                """,
                new MapSqlParameterSource("pipelineId", pipelineId),
                (rs, i) -> new PillarRow(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getInt("display_order")));
    }

    public Map<UUID, String> pipelineNames(Collection<UUID> pipelineIds) {
        if (pipelineIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT id, name FROM pipelines WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", pipelineIds),
                (rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /* ---------------------------------------------- submissions + evaluation */

    public record SubmissionRow(UUID id, UUID userId, UUID pipelineId, Instant evaluatedAt) {}

    /** Owner + pipeline of a member submission — empty for anonymous public ones. */
    public Optional<SubmissionRow> submission(UUID submissionId) {
        return jdbc.query("""
                SELECT s.id, s.user_id, a.pipeline_id, s.evaluated_at
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                WHERE s.id = :id AND s.user_id IS NOT NULL
                """,
                new MapSqlParameterSource("id", submissionId), this::submissionRow)
                .stream().findFirst();
    }

    public record MilestoneTaskRow(UUID taskId, UUID refId) {}

    /**
     * The cohort's milestone task of the given role (typed task spine, spec
     * §5), ANY publish status: for the compute (and the pending-tease guard)
     * the tag on the submission is authoritative — a task un-published after
     * members already answered it must still resolve. The journey/open side
     * keeps its own LIVE filter. Uniqueness is enforced at the task write;
     * LIMIT 1 is belt and braces.
     */
    public Optional<MilestoneTaskRow> milestoneTask(UUID cohortId, String role) {
        return jdbc.query("""
                SELECT t.id, t.ref_id
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                WHERE m.cohort_id = :cohortId AND t.task_type = 'ASSESSMENT'
                  AND t.milestone_role = :role
                LIMIT 1
                """,
                new MapSqlParameterSource("cohortId", cohortId).addValue("role", role),
                (rs, i) -> new MilestoneTaskRow(rs.getObject("id", UUID.class),
                        rs.getObject("ref_id", UUID.class)))
                .stream().findFirst();
    }

    /**
     * The user's latest cleanly-evaluated submission TAGGED to the given
     * milestone task — the same-pipeline-safe resolution. The pipeline
     * predicate guards against a tag that drifted from the designated pair
     * (also validated at the task write).
     */
    public Optional<SubmissionRow> latestEvaluatedTaggedSubmission(UUID userId, UUID taskId,
                                                                   UUID pipelineId) {
        return jdbc.query("""
                SELECT s.id, s.user_id, a.pipeline_id, s.evaluated_at
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                WHERE s.user_id = :userId
                  AND s.program_task_id = :taskId
                  AND a.pipeline_id = :pipelineId
                  AND s.status = 'EVALUATED'
                ORDER BY s.evaluated_at DESC NULLS LAST, s.created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("userId", userId).addValue("taskId", taskId)
                        .addValue("pipelineId", pipelineId),
                this::submissionRow)
                .stream().findFirst();
    }

    /**
     * The user's EARLIEST cleanly-evaluated submission for a pipeline — the
     * baseline side. Baseline means intake: a member re-taking the baseline
     * pipeline (check-ins allow it) must not silently shift the baseline to
     * the newest retake.
     */
    public Optional<SubmissionRow> earliestEvaluatedSubmission(UUID userId, UUID pipelineId) {
        return evaluatedSubmission(userId, pipelineId, "ASC");
    }

    /**
     * The user's EARLIEST cleanly-evaluated UNTAGGED sitting of a pipeline
     * strictly after {@code floor} — the comparison's twin of the journey's
     * own-instrument DISTANCE adoption ({@code TaskSpineRepository}'s
     * {@code ADOPTABLE_SITTINGS} — change one, change the other). Untagged,
     * because a tagged sitting already belongs to whatever task claimed it (a
     * check-in, another cohort's milestone); earliest, because that is the
     * sitting the journey tags on open — resolving a different one here would
     * persist a comparison the journey later contradicts; {@code evaluated_at}
     * required, because a reading without a time cannot sit on a timeline.
     * A null floor means there is no baseline reading to be after.
     */
    public Optional<SubmissionRow> earliestEvaluatedUntaggedSubmissionAfter(
            UUID userId, UUID pipelineId, Instant floor) {
        return jdbc.query("""
                SELECT s.id, s.user_id, a.pipeline_id, s.evaluated_at
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                WHERE s.user_id = :userId
                  AND a.pipeline_id = :pipelineId
                  AND s.status = 'EVALUATED'
                  AND s.program_task_id IS NULL
                  AND s.evaluated_at IS NOT NULL
                  AND (CAST(:floor AS timestamptz) IS NULL OR s.evaluated_at > :floor)
                ORDER BY s.evaluated_at, s.created_at
                LIMIT 1
                """,
                new MapSqlParameterSource("userId", userId).addValue("pipelineId", pipelineId)
                        .addValue("floor", floor == null ? null : java.sql.Timestamp.from(floor)),
                this::submissionRow)
                .stream().findFirst();
    }

    private Optional<SubmissionRow> evaluatedSubmission(UUID userId, UUID pipelineId,
                                                        String direction) {
        return jdbc.query("""
                SELECT s.id, s.user_id, a.pipeline_id, s.evaluated_at
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                WHERE s.user_id = :userId
                  AND a.pipeline_id = :pipelineId
                  AND s.status = 'EVALUATED'
                ORDER BY s.evaluated_at %1$s NULLS LAST, s.created_at %1$s
                LIMIT 1
                """.formatted(direction),
                new MapSqlParameterSource("userId", userId).addValue("pipelineId", pipelineId),
                this::submissionRow)
                .stream().findFirst();
    }

    public Map<UUID, Instant> submissionEvaluatedAt(Collection<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return Map.of();
        }
        // evaluated_at IS NOT NULL: absent keys, never NPEs — callers treat
        // a missing entry as "no timestamp to show".
        return jdbc.query("""
                SELECT id, evaluated_at FROM submissions
                WHERE id IN (:ids) AND evaluated_at IS NOT NULL
                """,
                new MapSqlParameterSource("ids", submissionIds),
                (rs, i) -> Map.entry(rs.getObject("id", UUID.class),
                        rs.getTimestamp("evaluated_at").toInstant()))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private SubmissionRow submissionRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp("evaluated_at");
        return new SubmissionRow(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("pipeline_id", UUID.class),
                ts == null ? null : ts.toInstant());
    }

    public Optional<BigDecimal> overallScore(UUID submissionId) {
        return jdbc.query("""
                SELECT overall_score_percentage FROM overall_summaries
                WHERE submission_id = :id
                """,
                new MapSqlParameterSource("id", submissionId),
                (rs, i) -> rs.getBigDecimal("overall_score_percentage"))
                .stream().findFirst();
    }

    public record PillarEvalRow(UUID pillarId, BigDecimal scorePercentage, String maturityLabel) {}

    /**
     * The STORED per-pillar scores + maturity labels of a submission. The label
     * is the band identity of record (RULING 4 / D-2 — never re-derive from
     * the score), which is exactly how this slice reuses
     * {@code ScoringService.deriveMaturityLabel} without duplicating it.
     */
    public Map<UUID, PillarEvalRow> pillarEvaluations(UUID submissionId) {
        return jdbc.query("""
                SELECT pillar_id, score_percentage, maturity_label
                FROM pillar_evaluations
                WHERE submission_id = :id AND ai_failed = false
                """,
                new MapSqlParameterSource("id", submissionId),
                (rs, i) -> new PillarEvalRow(rs.getObject("pillar_id", UUID.class),
                        rs.getBigDecimal("score_percentage"), rs.getString("maturity_label")))
                .stream().collect(Collectors.toMap(PillarEvalRow::pillarId, Function.identity()));
    }

    public record TrajectoryRow(UUID submissionId, String pipelineName,
                                BigDecimal overallScore, Instant evaluatedAt) {}

    /**
     * Every evaluated overall score of the user, oldest first — the trajectory
     * chart. When {@code cohortId} is given, the trajectory is scoped to that
     * cohort's own instruments ({@link CohortInstruments}, operator rule
     * 2026-08-14): a growth surface anchored to a designated cohort tells THAT
     * cohort's baseline → distance story, so a sitting on an unrelated scan
     * somebody assigned directly must not appear between its points. Null keeps
     * the global read — a member with no designated pair has no cohort story,
     * only "everything so far".
     */
    public List<TrajectoryRow> trajectory(UUID userId, UUID cohortId) {
        return jdbc.query("""
                SELECT s.id, p.name AS pipeline_name,
                       os.overall_score_percentage, s.evaluated_at
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                JOIN pipelines p ON p.id = a.pipeline_id
                JOIN overall_summaries os ON os.submission_id = s.id
                WHERE s.user_id = :userId AND s.status = 'EVALUATED'
                """
                + (cohortId == null ? ""
                        : "  AND " + CohortInstruments.ON_COHORT_INSTRUMENT.formatted("s", ":cohortId") + "\n")
                + "ORDER BY s.evaluated_at ASC NULLS LAST, s.created_at ASC",
                new MapSqlParameterSource("userId", userId).addValue("cohortId", cohortId),
                (rs, i) -> {
                    java.sql.Timestamp ts = rs.getTimestamp("evaluated_at");
                    return new TrajectoryRow(rs.getObject("id", UUID.class),
                            rs.getString("pipeline_name"),
                            rs.getBigDecimal("overall_score_percentage"),
                            ts == null ? null : ts.toInstant());
                });
    }

    /**
     * The per-pillar qualitative text blocks of a submission — the ONLY input
     * the shift-narrative job (spec §6) is allowed to see. Scores are
     * deliberately absent from this projection: "never generate from scores
     * alone" is enforced by construction, not by asking the prompt nicely.
     *
     * <p>The columns are jsonb string arrays; they are read as raw JSON and
     * parsed by the caller (this repository stays Jackson-free like the rest of
     * the slice's raw-SQL reads).
     */
    public Map<UUID, RawPillarText> pillarTextBlocks(UUID submissionId) {
        return jdbc.query("""
                SELECT pillar_id,
                       ai_whats_working::text     AS whats_working,
                       ai_what_can_improve::text  AS what_can_improve
                FROM pillar_evaluations
                WHERE submission_id = :id AND ai_failed = false
                """,
                new MapSqlParameterSource("id", submissionId),
                (rs, i) -> new RawPillarText(rs.getObject("pillar_id", UUID.class),
                        rs.getString("whats_working"), rs.getString("what_can_improve")))
                .stream().collect(Collectors.toMap(RawPillarText::pillarId, Function.identity()));
    }

    /** A pillar's two text blocks, still as raw jsonb text — parsed by the caller. */
    public record RawPillarText(UUID pillarId, String whatsWorkingJson, String whatCanImproveJson) {}

    /* ----------------------------------------------------- platform config */

    /**
     * The raw JSON document stored under a {@code platform_settings} key —
     * parsed (with defaults) by the caller. Used for the shift bands and the
     * narrative wording; the slice reads platform config by SQL because the
     * ArchUnit ratchet forbids a comparison→platform import.
     */
    public Optional<String> settingText(String key) {
        return jdbc.query("SELECT value_text FROM platform_settings WHERE key = :key",
                new MapSqlParameterSource("key", key),
                (rs, i) -> rs.getString("value_text"))
                .stream().findFirst().filter(s -> s != null && !s.isBlank());
    }
}
