package com.bvisionry.programflow.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.programaccess.MemberActivity;

/**
 * Cross-slice reads for the cohort-board founders matrix and the onboarding
 * checklist (redesign spec §2.3 / §11), raw SQL like
 * {@link TaskSpineRepository}: the matrix needs assessment scores
 * ({@code submissions}/{@code overall_summaries}/{@code pillar_evaluations}),
 * exercise review state and the shared last-activity fragment — none of which
 * programflow may import as Java types (ArchUnit ratchet).
 */
@Repository
public class CohortBoardReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CohortBoardReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------------- matrix triage */

    /**
     * The matrix row-end per founder: FRI ends (earliest/latest evaluated
     * overall score — the founder-profile trajectory rule), open items
     * (exercise submissions awaiting review), last activity, and the lowest
     * pillar score of the LATEST evaluated submission (the needs-attention
     * "pillar under threshold" input). Scalar subqueries per founder — a
     * cohort is tens of rows.
     */
    public record TriageRow(UUID userId, String name, BigDecimal friEarliest, BigDecimal friLatest,
                            long evaluatedCount, int awaitingReview,
                            OffsetDateTime lastActivityAt, BigDecimal minPillarScore) {}

    public List<TriageRow> triage(UUID orgId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT u.id, u.name,
                       (SELECT os.overall_score_percentage
                          FROM submissions s JOIN overall_summaries os ON os.submission_id = s.id
                         WHERE s.user_id = u.id AND s.status = 'EVALUATED'
                         ORDER BY s.evaluated_at ASC NULLS LAST, s.created_at ASC
                         LIMIT 1) AS fri_earliest,
                       (SELECT os.overall_score_percentage
                          FROM submissions s JOIN overall_summaries os ON os.submission_id = s.id
                         WHERE s.user_id = u.id AND s.status = 'EVALUATED'
                         ORDER BY s.evaluated_at DESC NULLS LAST, s.created_at DESC
                         LIMIT 1) AS fri_latest,
                       (SELECT count(*)
                          FROM submissions s JOIN overall_summaries os ON os.submission_id = s.id
                         WHERE s.user_id = u.id AND s.status = 'EVALUATED') AS evaluated_count,
                       (SELECT count(*)
                          FROM exercise_assignments ea
                          JOIN exercise_submissions es ON es.assignment_id = ea.id
                         WHERE ea.organization_id = :orgId AND ea.user_id = u.id
                           AND es.status = 'SUBMITTED') AS awaiting_review,
                       %s AS last_activity_at,
                       (SELECT min(pe.score_percentage)
                          FROM pillar_evaluations pe
                         WHERE pe.submission_id =
                               (SELECT s2.id FROM submissions s2
                                 WHERE s2.user_id = u.id AND s2.status = 'EVALUATED'
                                 ORDER BY s2.evaluated_at DESC NULLS LAST, s2.created_at DESC
                                 LIMIT 1)) AS min_pillar_score
                FROM users u
                WHERE u.id IN (:userIds)
                """.formatted(MemberActivity.LAST_ACTIVITY.formatted("u")),
                new MapSqlParameterSource("orgId", orgId).addValue("userIds", userIds),
                (rs, i) -> new TriageRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBigDecimal("fri_earliest"), rs.getBigDecimal("fri_latest"),
                        rs.getLong("evaluated_count"), rs.getInt("awaiting_review"),
                        rs.getObject("last_activity_at", OffsetDateTime.class),
                        rs.getBigDecimal("min_pillar_score")));
    }

    /* -------------------------------------------------- platform threshold */

    /**
     * A platform_settings int with a default — the
     * {@code AttentionThresholdsService} read rule, expressed as SQL because
     * programflow may not import the organization feature's service.
     */
    public int platformInt(String key, int fallback) {
        List<Integer> rows = jdbc.query(
                "SELECT value_int FROM platform_settings WHERE key = :key",
                new MapSqlParameterSource("key", key),
                (rs, i) -> (Integer) rs.getObject("value_int"));
        return rows.isEmpty() || rows.get(0) == null ? fallback : rows.get(0);
    }

    /* ---------------------------------------------- onboarding checklist */

    /**
     * The §11 checklist booleans over the BILLING FAMILY of {@code orgId}
     * (root + sub-orgs — members, cohorts and coaches live on sub-orgs while
     * the admin onboards from wherever). One round trip.
     */
    public record ChecklistRow(boolean invitedMembers, boolean assignedFirstAssessment,
                               boolean launchedCohort, boolean assignedCoach) {}

    public ChecklistRow checklist(UUID orgId) {
        String family = """
                (SELECT fam.id FROM organizations fam, organizations self
                  WHERE self.id = :orgId
                    AND COALESCE(fam.parent_organization_id, fam.id)
                      = COALESCE(self.parent_organization_id, self.id))""";
        return jdbc.queryForObject("""
                SELECT
                  (EXISTS (SELECT 1 FROM invitations i WHERE i.organization_id IN %1$s)
                   OR EXISTS (SELECT 1 FROM users u
                               WHERE u.organization_id IN %1$s AND u.role = 'MEMBER'))
                      AS invited_members,
                  EXISTS (SELECT 1 FROM assignments a WHERE a.organization_id IN %1$s)
                      AS assigned_first_assessment,
                  EXISTS (SELECT 1 FROM cohort_launch_ledger l
                           WHERE l.org_id IN %1$s) AS launched_cohort,
                  EXISTS (SELECT 1 FROM coach_assignments ca WHERE ca.org_id IN %1$s)
                      AS assigned_coach
                """.formatted(family),
                new MapSqlParameterSource("orgId", orgId),
                (rs, i) -> new ChecklistRow(rs.getBoolean("invited_members"),
                        rs.getBoolean("assigned_first_assessment"),
                        rs.getBoolean("launched_cohort"),
                        rs.getBoolean("assigned_coach")));
    }
}
