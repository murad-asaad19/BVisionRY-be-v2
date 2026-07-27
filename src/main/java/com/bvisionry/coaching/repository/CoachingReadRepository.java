package com.bvisionry.coaching.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.programaccess.ProgramAudience;

/**
 * Cross-feature reads for the coach console, expressed as raw SQL through
 * {@link NamedParameterJdbcTemplate}.
 *
 * <p><strong>Why raw SQL rather than the owning features' repositories.</strong>
 * The console reads cohorts + program progress ({@code programflow}), pillar
 * scores ({@code evaluation}/{@code assessment}) and exercise submissions
 * ({@code exercise}); the ArchUnit ratchet ({@code noCrossFeatureDependencies})
 * forbids new feature→feature imports, so — following the
 * {@code common.gdpr.PersonalDataRepository} precedent — this class depends on
 * the schema instead and imports no other feature's types.
 *
 * <p><strong>Every query carries the tenant + visibility predicate in the SQL
 * itself.</strong> WHO is visible composes the shared
 * {@link CoachAccess#VISIBLE_MEMBER_PREDICATE} (never restated, so it cannot
 * fork from the review-loop check). WHAT of their journey is visible follows
 * the GRAIN of the grant ({@link #GRANTED_COHORT}): a cohort grant scopes
 * cohort names, modules and completion to the granted cohorts; a direct grant
 * exposes the founder's full journey; holding both is the union (= full).
 * Nothing is post-filtered in Java.
 */
@Repository
public class CoachingReadRepository {

    /** WHO: the coach may see founder {@code u} at all. Binds {@code :orgId}, {@code :coachId}. */
    private static final String VISIBLE_FOUNDER = """
            u.organization_id = :orgId
              AND u.role = 'MEMBER'
              AND u.status = 'ACTIVE'
              AND\s""" + CoachAccess.VISIBLE_MEMBER_PREDICATE.formatted("u.id");

    /**
     * WHAT: cohort {@code c} is inside the coach's grant grain for founder
     * {@code %1$s} — either the coach holds a DIRECT grant on the founder
     * (full journey) or this specific cohort is granted. Binds {@code :orgId},
     * {@code :coachId}.
     */
    private static final String GRANTED_COHORT = """
            (EXISTS (SELECT 1 FROM coach_assignments dg
                     WHERE dg.org_id = :orgId AND dg.coach_id = :coachId
                       AND dg.member_id = %1$s)
             OR EXISTS (SELECT 1 FROM coach_assignments cg
                        WHERE cg.org_id = :orgId AND cg.coach_id = :coachId
                          AND cg.cohort_id = c.id))""";

    /**
     * Module {@code m}'s audience includes user {@code %1$s} — the shared
     * fragment, so this console and the ROI report can never quote different
     * completion numbers for the same founder.
     */
    private static final String AUDIENCE = ProgramAudience.INCLUDES_USER;

    /**
     * The roster row shape: grant-scoped cohort names plus grant-scoped
     * program totals, both LATERAL so the whole query is O(visible founders),
     * never O(org members × live tasks).
     */
    private static final String ROSTER_SELECT = """
            SELECT u.id, u.name,
                   cn.names                 AS cohort_names,
                   COALESCE(p.total, 0)     AS total_tasks,
                   COALESCE(p.submitted, 0) AS submitted_tasks
            FROM users u
            LEFT JOIN LATERAL (
                SELECT string_agg(c.name, ', ' ORDER BY c.position, c.name) AS names
                FROM cohort_members cm
                JOIN cohorts c ON c.id = cm.cohort_id AND c.org_id = :orgId
                WHERE cm.user_id = u.id
                  AND %1$s
            ) cn ON true
            LEFT JOIN LATERAL (
                SELECT count(DISTINCT t.id)       AS total,
                       count(DISTINCT ps.task_id) AS submitted
                FROM cohort_members cm
                JOIN cohorts c          ON c.id = cm.cohort_id AND c.org_id = :orgId
                JOIN program_modules m  ON m.cohort_id = c.id
                JOIN program_tasks t    ON t.module_id = m.id AND t.status = 'LIVE'
                LEFT JOIN program_submissions ps
                       ON ps.task_id = t.id AND ps.user_id = u.id AND ps.status = 'SUBMITTED'
                WHERE cm.user_id = u.id
                  AND %1$s
                  AND %2$s
            ) p ON true
            """.formatted(GRANTED_COHORT.formatted("u.id"), AUDIENCE.formatted("u.id"));

    private final NamedParameterJdbcTemplate jdbc;

    public CoachingReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------------- console reads */

    public record RosterRow(UUID id, String name, String cohortNames,
                            int totalTasks, int submittedTasks) {}

    /**
     * Every founder the coach may see, with grant-scoped completion counts.
     * ponytail: unbounded — a caseload is tens of founders today; paginate
     * when orgs run cohorts in the hundreds.
     */
    public List<RosterRow> roster(UUID orgId, UUID coachId) {
        return jdbc.query(
                ROSTER_SELECT + "WHERE " + VISIBLE_FOUNDER + " ORDER BY u.name, u.id",
                params(orgId, coachId),
                (rs, i) -> new RosterRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("cohort_names"),
                        rs.getInt("total_tasks"), rs.getInt("submitted_tasks")));
    }

    /** One visible founder's roster row, or empty when outside the union → 404. */
    public Optional<RosterRow> visibleFounder(UUID orgId, UUID coachId, UUID founderId) {
        return jdbc.query(
                ROSTER_SELECT + "WHERE u.id = :founderId AND " + VISIBLE_FOUNDER,
                params(orgId, coachId).addValue("founderId", founderId),
                (rs, i) -> new RosterRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("cohort_names"),
                        rs.getInt("total_tasks"), rs.getInt("submitted_tasks")))
                .stream().findFirst();
    }

    public record CoachOfMemberRow(UUID id, String name, String bookingUrl) {}

    /**
     * The reverse of {@link #roster}: every coach who may see {@code memberId},
     * with the booking link they published. Composes the SAME assignment-union
     * relation ({@link CoachAccess#VISIBLE_COACH_PREDICATE}) read backwards, so
     * a founder can never be shown a coach who cannot see them, nor miss one
     * who can.
     *
     * <p>The two predicates the reverse direction has to add itself, because the
     * shared fragment deliberately says nothing about the coach's own row (see
     * its javadoc — forwards, the coach is the authenticated caller):
     * {@code role = 'COACH'} and {@code status = 'ACTIVE'}. Without them a
     * suspended coach, or one demoted out of the role while a stale grant
     * survives, would still be offered to the founder as bookable.
     *
     * <p><strong>{@code cu.organization_id = :orgId} is load-bearing on its own.</strong>
     * The shared relation pins the GRANT and the FOUNDER to {@code :orgId}; it
     * says nothing about the coach's org, because forwards that is the caller's
     * own. A hand-written grant naming a foreign coach — {@code org_id} and
     * {@code member_id} in org A, {@code coach_id} in org B — satisfies the
     * relation entirely, and without this line the founder would be handed
     * another tenant's coach and their booking link. Covered by
     * {@code aCrossOrgGrantNeverSurfacesAForeignCoach}.
     */
    public List<CoachOfMemberRow> coachesOfMember(UUID orgId, UUID memberId) {
        return jdbc.query("""
                SELECT cu.id, cu.name, cp.booking_url
                FROM users cu
                LEFT JOIN coach_profiles cp ON cp.coach_id = cu.id
                WHERE cu.organization_id = :orgId
                  AND cu.role = 'COACH'
                  AND cu.status = 'ACTIVE'
                  AND %s
                ORDER BY cu.name, cu.id
                """.formatted(CoachAccess.VISIBLE_COACH_PREDICATE.formatted("cu.id")),
                new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId),
                (rs, i) -> new CoachOfMemberRow(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("booking_url")));
    }

    public record PillarScoreRow(String pillarName, BigDecimal scorePercentage,
                                 String maturityLabel, OffsetDateTime evaluatedAt) {}

    /** The founder's latest evaluated assessment, one row per pillar. */
    public List<PillarScoreRow> pillarScores(UUID orgId, UUID coachId, UUID founderId) {
        return jdbc.query("""
                SELECT p.name, pe.score_percentage, pe.maturity_label, pe.evaluated_at
                FROM pillar_evaluations pe
                JOIN pillars p      ON p.id = pe.pillar_id
                JOIN submissions s  ON s.id = pe.submission_id
                WHERE s.user_id = :founderId
                  AND s.id = (SELECT s2.id FROM submissions s2
                              WHERE s2.user_id = :founderId
                                AND EXISTS (SELECT 1 FROM pillar_evaluations pe2
                                            WHERE pe2.submission_id = s2.id)
                              ORDER BY s2.submitted_at DESC NULLS LAST, s2.created_at DESC
                              LIMIT 1)
                  AND EXISTS (SELECT 1 FROM users u WHERE u.id = :founderId AND %s)
                ORDER BY p.display_order, p.name
                """.formatted(VISIBLE_FOUNDER),
                params(orgId, coachId).addValue("founderId", founderId),
                (rs, i) -> new PillarScoreRow(rs.getString("name"),
                        rs.getBigDecimal("score_percentage"), rs.getString("maturity_label"),
                        rs.getObject("evaluated_at", OffsetDateTime.class)));
    }

    public record ModuleProgressRow(String cohortName, String moduleName,
                                    int totalTasks, int submittedTasks) {}

    /** Per-module progress over the GRANTED cohorts only (audience-filtered LIVE tasks). */
    public List<ModuleProgressRow> moduleProgress(UUID orgId, UUID coachId, UUID founderId) {
        return jdbc.query("""
                SELECT c.name AS cohort_name, m.name AS module_name,
                       count(DISTINCT t.id)       AS total,
                       count(DISTINCT ps.task_id) AS submitted
                FROM cohort_members cm
                JOIN cohorts c          ON c.id = cm.cohort_id AND c.org_id = :orgId
                JOIN program_modules m  ON m.cohort_id = c.id
                JOIN program_tasks t    ON t.module_id = m.id AND t.status = 'LIVE'
                LEFT JOIN program_submissions ps
                       ON ps.task_id = t.id AND ps.user_id = cm.user_id AND ps.status = 'SUBMITTED'
                WHERE cm.user_id = :founderId
                  AND %s
                  AND %s
                  AND EXISTS (SELECT 1 FROM users u WHERE u.id = :founderId AND %s)
                GROUP BY c.name, c.position, m.name, m.position
                ORDER BY c.position, c.name, m.position, m.name
                """.formatted(GRANTED_COHORT.formatted(":founderId"),
                        AUDIENCE.formatted("cm.user_id"), VISIBLE_FOUNDER),
                params(orgId, coachId).addValue("founderId", founderId),
                (rs, i) -> new ModuleProgressRow(rs.getString("cohort_name"),
                        rs.getString("module_name"), rs.getInt("total"), rs.getInt("submitted")));
    }

    public record ExerciseSubmissionRow(UUID assignmentId, String exerciseName,
                                        String status, OffsetDateTime submittedAt) {}

    /** The founder's exercise assignments + submission state, for the review links. */
    public List<ExerciseSubmissionRow> exerciseSubmissions(UUID orgId, UUID coachId, UUID founderId) {
        return jdbc.query("""
                SELECT ea.id AS assignment_id, et.name AS exercise_name,
                       es.status, es.submitted_at
                FROM exercise_assignments ea
                JOIN exercise_templates et ON et.id = ea.template_id
                LEFT JOIN exercise_submissions es ON es.assignment_id = ea.id
                WHERE ea.organization_id = :orgId
                  AND ea.user_id = :founderId
                  AND EXISTS (SELECT 1 FROM users u WHERE u.id = :founderId AND %s)
                ORDER BY ea.created_at DESC
                """.formatted(VISIBLE_FOUNDER),
                params(orgId, coachId).addValue("founderId", founderId),
                (rs, i) -> new ExerciseSubmissionRow(rs.getObject("assignment_id", UUID.class),
                        rs.getString("exercise_name"), rs.getString("status"),
                        rs.getObject("submitted_at", OffsetDateTime.class)));
    }

    /* -------------------------------------- admin-side validation + naming */

    public record OrgUserRow(UUID id, String name, String email, String role) {}

    /** A user constrained to the org — empty when absent or foreign. */
    public Optional<OrgUserRow> userInOrg(UUID orgId, UUID userId) {
        return jdbc.query("""
                SELECT id, name, email, role FROM users
                WHERE id = :userId AND organization_id = :orgId
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("userId", userId),
                (rs, i) -> new OrgUserRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getString("role")))
                .stream().findFirst();
    }

    /** The cohort's name, constrained to the org — empty when absent or foreign. */
    public Optional<String> cohortNameInOrg(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT name FROM cohorts WHERE id = :cohortId AND org_id = :orgId
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("cohortId", cohortId),
                (rs, i) -> rs.getString("name"))
                .stream().findFirst();
    }

    /** Batch name lookup for the assignment list, org-constrained. */
    public Map<UUID, OrgUserRow> usersInOrg(UUID orgId, Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("""
                SELECT id, name, email, role FROM users
                WHERE organization_id = :orgId AND id IN (:ids)
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("ids", userIds),
                (rs, i) -> new OrgUserRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getString("role")))
                .stream().collect(Collectors.toMap(OrgUserRow::id, Function.identity()));
    }

    /** Batch cohort-name lookup for the assignment list, org-constrained. */
    public Map<UUID, String> cohortNamesInOrg(UUID orgId, Set<UUID> cohortIds) {
        if (cohortIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("""
                SELECT id, name FROM cohorts WHERE org_id = :orgId AND id IN (:ids)
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("ids", cohortIds),
                (rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static MapSqlParameterSource params(UUID orgId, UUID coachId) {
        return new MapSqlParameterSource("orgId", orgId).addValue("coachId", coachId);
    }
}
