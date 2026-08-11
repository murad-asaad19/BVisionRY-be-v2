package com.bvisionry.insights;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Quantitative benchmarking reads: a cohort's / an org's pillar scores against
 * the org's own distribution and the anonymous platform-wide distribution
 * (roadmap §7 item 19), as raw SQL through {@link NamedParameterJdbcTemplate}.
 *
 * <p><strong>Why raw SQL rather than the owning features' repositories.</strong>
 * The distributions read submissions + pillar evaluations ({@code assessment}/
 * {@code evaluation}) and cohorts ({@code programflow}); the ArchUnit ratchet
 * ({@code noCrossFeatureDependencies}) forbids new feature→feature imports, so —
 * following {@code common.gdpr.PersonalDataRepository} and
 * {@code coaching.repository.CoachingReadRepository} — this class depends on the
 * schema instead and imports no other feature's types.
 *
 * <p><strong>The platform query is the one deliberately cross-tenant read on the
 * insights surface, and it is structurally incapable of returning
 * org-attributable data:</strong> it SELECTs only aggregate functions, GROUPs BY
 * pillar (never by org), carries no org column anywhere in its output, and
 * enforces BOTH suppression floors in the SQL itself — an under-sampled
 * platform number never even reaches Java
 * ({@code benchmark_min_sample: 30}, {@code benchmark_anonymity: AGGREGATE_ONLY}).
 *
 * <p><strong>Complementary suppression.</strong> The total clearing the floor is
 * not enough: the caller knows their own founders' exact scores from own-tenant
 * surfaces (even while their org SEGMENT is suppressed), so
 * {@code n·mean − Σ(own scores)} would recover the sub-floor foreign residual —
 * at own 29 / platform 30 it recovers the single foreign founder's score
 * outright. So the platform row also requires {@code MIN_SAMPLE} samples
 * OUTSIDE the caller's reach (agent-decisions: "Complementary suppression on
 * the platform aggregate"). The cohort segment needs no extra floor — a cohort
 * is a subset of the org, so the org complement is the binding one.
 *
 * <p><strong>The excluded set is the path org's one-level FAMILY</strong> —
 * root(orgId) plus every child of that root — not the path org alone.
 * {@code OrgAccessGuard} admits a parent org's admins on any of its sub-orgs'
 * paths, every org-scoped score surface uses that guard, and V136 puts every
 * root's founders into a "General" sub-org, so hierarchy is the default
 * topology: a root admin knows ALL family scores and could otherwise address
 * one sub-org's path to difference a sibling (or an external founder) out of
 * the aggregate. The family is a pure function of the guarded {@code :orgId}
 * and a superset of every admitted caller's knowledge, so the gate is
 * caller-independent. A sub-org's own admin is thereby over-suppressed
 * (siblings they cannot read are excluded from their complement too) — that is
 * the safe direction, deliberately taken. The hierarchy is one level by
 * construction (the service layer rejects sub-orgs under sub-orgs); if that
 * ever deepens, this exclusion must close over the full descendant set.
 *
 * <p>The org and cohort queries carry the tenant predicate
 * ({@code a.organization_id = :orgId}) in the SQL; their score aggregates are
 * CASE-gated on the same {@code :minSample} bound so an under-sampled mean or
 * percentile is NULL in the result set, never a number post-filtered in Java.
 * Their sample <em>counts</em> are returned — that is the caller's own tenant
 * data, and the UI needs it to name what unlocks the benchmark.
 *
 * <p>ponytail: live aggregation, no materialised table. One founder is one row
 * per pillar (V102 unique (submission_id, pillar_id)); the scan is
 * O(submissions per pipeline), fine for tens of thousands of rows. If platform
 * volume ever makes this slow, add an index on submissions(assignment_id) or a
 * nightly rollup — not before.
 */
@Repository
public class BenchmarkReadRepository {

    /**
     * {@code benchmark_min_sample} (agent-policy defaults): below this many
     * founders in a segment the segment shows "insufficient data", never a
     * number. Enforced inside every distribution query.
     */
    public static final int MIN_SAMPLE = 30;

    /** One pillar of the pipeline — the axis every response row hangs on. */
    public record PillarRef(UUID id, String name) {}

    /**
     * One pillar's distribution within one segment. {@code mean}/{@code p25}/
     * {@code p75} are NULL whenever {@code sampleSize < MIN_SAMPLE}
     * — the suppression happens in SQL, so no under-sampled number exists here.
     */
    public record SegmentRow(UUID pillarId, int sampleSize, BigDecimal mean,
                             BigDecimal p25, BigDecimal p75) {}

    /**
     * Latest evaluated submission per founder for the pipeline — one founder is
     * one sample, re-assessments never double-count. Same latest-pick ordering
     * as the coach console's pillar read. {@code %s} takes the segment's extra
     * tenant predicate ({@code ""} for platform-wide); the org column feeds the
     * platform query's complement floor.
     *
     * <p>{@code status = 'EVALUATED'} is the population, not merely the
     * presence of {@code pillar_evaluations} rows: a run that fails its repair
     * retries persists {@code ai_failed} pillars scored {@code 0.00} under
     * {@code NEEDS_REVIEW} ("fail loud, not quiet",
     * {@link com.bvisionry.common.enums.SubmissionStatus}). Those zeros are
     * quarantined failures, and letting them into a distribution would drag
     * every mean and percentile — including the cross-tenant platform
     * aggregate — toward zero. It also excludes {@code PENDING_REEDIT}, whose
     * scores are mid-rewrite.
     */
    private static final String LATEST_EVALUATED = """
            SELECT DISTINCT ON (s.user_id) s.id, a.organization_id
            FROM submissions s
            JOIN assignments a ON a.id = s.assignment_id
            WHERE a.pipeline_id = :pipelineId
              AND s.status = 'EVALUATED'
              AND EXISTS (SELECT 1 FROM pillar_evaluations pe WHERE pe.submission_id = s.id)
              %s
            ORDER BY s.user_id, s.submitted_at DESC NULLS LAST, s.created_at DESC
            """;

    /** Aggregate columns, gated on {@code %1$s} (the sufficiency predicate). */
    private static final String GATED_AGGREGATES = """
            count(*) AS n,
            CASE WHEN %1$s THEN round(avg(pe.score_percentage), 1) END AS mean,
            CASE WHEN %1$s THEN round(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY pe.score_percentage) AS numeric), 1) END AS p25,
            CASE WHEN %1$s THEN round(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY pe.score_percentage) AS numeric), 1) END AS p75
            """;

    private static final String SUFFICIENT = "count(*) >= :minSample";

    /**
     * The complement floor: at least {@code MIN_SAMPLE} of the samples belong
     * to orgs OUTSIDE the path org's one-level family (root + all of the
     * root's children — see the class doc), so no admitted caller can
     * difference everything they know out of the aggregate and read a
     * sub-floor foreign residual. {@code COALESCE(parent, id)} is the root of
     * either a root org or a child, so one predicate covers both path shapes.
     */
    private static final String FOREIGN_SUFFICIENT = """
            count(*) FILTER (WHERE l.organization_id NOT IN (
                SELECT o.id FROM organizations o
                WHERE COALESCE(o.parent_organization_id, o.id) =
                      (SELECT COALESCE(p.parent_organization_id, p.id)
                       FROM organizations p WHERE p.id = :orgId)
            )) >= :minSample""";

    private static final String DISTRIBUTION = """
            WITH latest AS (%s)
            SELECT pe.pillar_id, %s
            FROM latest l
            JOIN pillar_evaluations pe ON pe.submission_id = l.id
            GROUP BY pe.pillar_id
            """;

    /** The caller's org owns the assessment context of the sample. */
    private static final String ORG_PREDICATE = "AND a.organization_id = :orgId";

    /** …and the founder is enrolled in the requested cohort OF THAT ORG. */
    private static final String COHORT_PREDICATE = ORG_PREDICATE + """

              AND EXISTS (SELECT 1 FROM cohort_members cm
                          JOIN cohorts c ON c.id = cm.cohort_id
                          WHERE cm.user_id = s.user_id
                            AND c.id = :cohortId
                            AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BenchmarkReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when the pipeline exists and is PUBLISHED. The axis query below
     * would otherwise act as an existence oracle handing any org admin a DRAFT
     * pipeline's pillar names; the service 404s instead.
     */
    public boolean pipelinePublished(UUID pipelineId) {
        return !jdbc.query(
                "SELECT 1 FROM pipelines WHERE id = :pipelineId AND status = 'PUBLISHED'",
                new MapSqlParameterSource("pipelineId", pipelineId),
                (rs, i) -> 1).isEmpty();
    }

    /**
     * The pipeline's STANDARD pillars in display order — the response axis. The
     * auto-created PERSONAL pillar ("General Information", V30) is excluded:
     * {@code EvaluationEngine} strips it before scoring, so it can never hold a
     * pillar evaluation and would otherwise render as a permanent
     * "insufficient data" first row on the benchmark panel.
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

    /** The cohort's name, constrained to the org's assignments (spec §13) — empty when absent or foreign. */
    public Optional<String> cohortNameInOrg(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT c.name FROM cohorts c
                JOIN cohort_orgs cox ON cox.cohort_id = c.id AND cox.org_id = :orgId
                WHERE c.id = :cohortId
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("cohortId", cohortId),
                (rs, i) -> rs.getString("name"))
                .stream().findFirst();
    }

    /**
     * The anonymous platform-wide distribution, keyed by pillar and gated PER
     * CALLER: aggregates only, grouped by pillar, and {@code HAVING} both the
     * total floor and the complement floor (see the class doc) in the SQL — a
     * pillar below either floor is simply absent from the result set, so its
     * sample count is not observable either.
     */
    public Map<UUID, SegmentRow> platformDistribution(UUID orgId, UUID pipelineId) {
        String sql = DISTRIBUTION.formatted(
                LATEST_EVALUATED.formatted(""),
                GATED_AGGREGATES.formatted(SUFFICIENT))
                + "HAVING " + SUFFICIENT + " AND " + FOREIGN_SUFFICIENT;
        return byPillar(sql, params(pipelineId).addValue("orgId", orgId));
    }

    /** The caller's own org's distribution (counts always, scores gated). */
    public Map<UUID, SegmentRow> orgDistribution(UUID orgId, UUID pipelineId) {
        String sql = DISTRIBUTION.formatted(
                LATEST_EVALUATED.formatted(ORG_PREDICATE),
                GATED_AGGREGATES.formatted(SUFFICIENT));
        return byPillar(sql, params(pipelineId).addValue("orgId", orgId));
    }

    /** One cohort of the caller's org (counts always, scores gated). */
    public Map<UUID, SegmentRow> cohortDistribution(UUID orgId, UUID cohortId, UUID pipelineId) {
        String sql = DISTRIBUTION.formatted(
                LATEST_EVALUATED.formatted(COHORT_PREDICATE),
                GATED_AGGREGATES.formatted(SUFFICIENT));
        return byPillar(sql, params(pipelineId)
                .addValue("orgId", orgId).addValue("cohortId", cohortId));
    }

    private Map<UUID, SegmentRow> byPillar(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params,
                (rs, i) -> new SegmentRow(rs.getObject("pillar_id", UUID.class),
                        rs.getInt("n"), rs.getBigDecimal("mean"), rs.getBigDecimal("p25"),
                        rs.getBigDecimal("p75")))
                .stream().collect(Collectors.toMap(SegmentRow::pillarId, Function.identity()));
    }

    private static MapSqlParameterSource params(UUID pipelineId) {
        return new MapSqlParameterSource("pipelineId", pipelineId)
                .addValue("minSample", MIN_SAMPLE);
    }
}
