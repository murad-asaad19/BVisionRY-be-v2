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
import com.bvisionry.common.programaccess.TaskCompletion;

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
     * {@code %1$s} — the coach holds a DIRECT grant on the founder (full
     * journey), or an ORG-WIDE grant (V176 — strictly wider than a direct one,
     * so it grants the same full journey), or this specific cohort is granted.
     * Binds {@code :orgId}, {@code :coachId}.
     */
    private static final String GRANTED_COHORT = """
            (EXISTS (SELECT 1 FROM coach_assignments dg
                     WHERE dg.org_id = :orgId AND dg.coach_id = :coachId
                       AND (dg.member_id = %1$s
                            OR (dg.cohort_id IS NULL AND dg.member_id IS NULL)))
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
     * "Done" for the task the composing query exposes as {@code t} — the
     * shared per-type spine rule ({@code ProgramRules} semantics), so the
     * roster, the module progress, the engagement record and the ROI report
     * can never quote different completion numbers for the same founder.
     */
    private static final String TASK_DONE = TaskCompletion.DONE_FOR_USER;

    /**
     * The founder's own last-activity instant — the shared fragment
     * ({@link com.bvisionry.common.programaccess.MemberActivity}, promoted on
     * its third consumer), so the roster, the founder profile and the cohort
     * board can never disagree about "last seen". {@code %1$s} is the user
     * alias.
     */
    private static final String LAST_ACTIVITY =
            com.bvisionry.common.programaccess.MemberActivity.LAST_ACTIVITY;

    /**
     * The roster row shape: grant-scoped cohort names, grant-scoped per-type
     * program completion, the FRI trajectory ends (latest + Δ vs earliest —
     * the founder-profile rule: evaluated submissions ordered by
     * {@code evaluated_at ASC NULLS LAST, created_at ASC}), the lowest
     * incomplete module, the open-items split and the founder's last
     * activity. All LATERAL/scalar subqueries, so the whole query stays
     * O(visible founders), never O(org members × live tasks).
     *
     * <p><strong>"Unread replies" is a proxy, commented honestly:</strong>
     * there is no read tracking, so it counts OPEN exercise-comment threads on
     * the founder's submissions whose LAST message (root or reply, newest
     * {@code created_at}, id tiebreak) was authored by the founder — i.e. the
     * ball is in the coach's court. A thread the coach answered last, or a
     * RESOLVED one, does not count.
     */
    private static final String ROSTER_SELECT = """
            SELECT u.id, u.name,
                   cn.names                 AS cohort_names,
                   COALESCE(p.total, 0)     AS total_tasks,
                   COALESCE(p.done, 0)      AS submitted_tasks,
                   fri.latest               AS fri_latest,
                   CASE WHEN fri.taken >= 2 THEN fri.latest - fri.earliest END AS fri_delta,
                   cur.module_name          AS current_module,
                   (SELECT count(*)
                    FROM exercise_assignments ea
                    JOIN exercise_submissions es ON es.assignment_id = ea.id
                    WHERE ea.organization_id = :orgId AND ea.user_id = u.id
                      AND es.status = 'SUBMITTED') AS awaiting_review,
                   (SELECT count(*)
                    FROM exercise_comments rc
                    JOIN exercise_submissions res ON res.id = rc.submission_id
                    JOIN exercise_assignments rea ON rea.id = res.assignment_id
                    WHERE rea.organization_id = :orgId AND rea.user_id = u.id
                      AND rc.parent_id IS NULL AND rc.status = 'OPEN'
                      AND (SELECT lc.author_id FROM exercise_comments lc
                           WHERE lc.id = rc.id OR lc.parent_id = rc.id
                           ORDER BY lc.created_at DESC, lc.id DESC LIMIT 1) = u.id
                   ) AS unread_replies,
                   %3$s AS last_activity_at
            FROM users u
            LEFT JOIN LATERAL (
                SELECT string_agg(c.name, ', ' ORDER BY c.position, c.name) AS names
                FROM cohort_members cm
                JOIN cohorts c ON c.id = cm.cohort_id AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId)
                WHERE cm.user_id = u.id
                  AND %1$s
            ) cn ON true
            LEFT JOIN LATERAL (
                SELECT count(*)                    AS total,
                       count(*) FILTER (WHERE %4$s) AS done
                FROM cohort_members cm
                JOIN cohorts c          ON c.id = cm.cohort_id AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId)
                JOIN program_modules m  ON m.cohort_id = c.id
                JOIN program_tasks t    ON t.module_id = m.id AND t.status = 'LIVE'
                WHERE cm.user_id = u.id
                  AND %1$s
                  AND %2$s
                  AND %5$s
            ) p ON true
            LEFT JOIN LATERAL (
                SELECT count(*) AS taken,
                       max(f.score) FILTER (WHERE f.rn_first = 1) AS earliest,
                       max(f.score) FILTER (WHERE f.rn_last = 1)  AS latest
                FROM (SELECT os.overall_score_percentage AS score,
                             row_number() OVER (ORDER BY s.evaluated_at ASC NULLS LAST,
                                                         s.created_at ASC)  AS rn_first,
                             row_number() OVER (ORDER BY s.evaluated_at DESC,
                                                         s.created_at DESC) AS rn_last
                      FROM submissions s
                      JOIN overall_summaries os ON os.submission_id = s.id
                      WHERE s.user_id = u.id AND s.status = 'EVALUATED') f
            ) fri ON true
            LEFT JOIN LATERAL (
                SELECT m.name AS module_name
                FROM cohort_members cm
                JOIN cohorts c         ON c.id = cm.cohort_id AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId)
                JOIN program_modules m ON m.cohort_id = c.id
                WHERE cm.user_id = u.id
                  AND %1$s
                  AND %2$s
                  AND EXISTS (SELECT 1 FROM program_tasks t
                              WHERE t.module_id = m.id AND t.status = 'LIVE'
                                AND NOT COALESCE(%4$s, false)
                                AND %5$s)
                ORDER BY c.position, c.name, m.position, m.name
                LIMIT 1
            ) cur ON true
            """.formatted(GRANTED_COHORT.formatted("u.id"), AUDIENCE.formatted("u.id"),
                    LAST_ACTIVITY.formatted("u"), TASK_DONE.formatted("u.id"),
                    // ProgramRules.gates in SQL: a COURSE task the founder's org
                    // cannot see counts in neither side of the fraction and never
                    // pins a module as "current".
                    TaskCompletion.COUNTS_FOR_USER.formatted("u.id"));

    private final NamedParameterJdbcTemplate jdbc;

    public CoachingReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------------- console reads */

    public record RosterRow(UUID id, String name, String cohortNames,
                            int totalTasks, int submittedTasks,
                            BigDecimal friLatest, BigDecimal friDelta,
                            String currentModule, int awaitingReview, int unreadReplies,
                            OffsetDateTime lastActivityAt) {}

    /**
     * Every founder the coach may see, with grant-scoped completion counts and
     * the triage columns (FRI + Δ, current module, open-items split, last
     * activity). ponytail: unbounded — a caseload is tens of founders today;
     * paginate when orgs run cohorts in the hundreds.
     */
    public List<RosterRow> roster(UUID orgId, UUID coachId) {
        return jdbc.query(
                ROSTER_SELECT + "WHERE " + VISIBLE_FOUNDER + " ORDER BY u.name, u.id",
                params(orgId, coachId),
                CoachingReadRepository::rosterRow);
    }

    /** One visible founder's roster row, or empty when outside the union → 404. */
    public Optional<RosterRow> visibleFounder(UUID orgId, UUID coachId, UUID founderId) {
        return jdbc.query(
                ROSTER_SELECT + "WHERE u.id = :founderId AND " + VISIBLE_FOUNDER,
                params(orgId, coachId).addValue("founderId", founderId),
                CoachingReadRepository::rosterRow)
                .stream().findFirst();
    }

    private static RosterRow rosterRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RosterRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("cohort_names"),
                rs.getInt("total_tasks"), rs.getInt("submitted_tasks"),
                rs.getBigDecimal("fri_latest"), rs.getBigDecimal("fri_delta"),
                rs.getString("current_module"),
                rs.getInt("awaiting_review"), rs.getInt("unread_replies"),
                rs.getObject("last_activity_at", OffsetDateTime.class));
    }

    public record QueueRow(UUID assignmentId, UUID founderId, String founderName,
                           String exerciseName, OffsetDateTime submittedAt,
                           OffsetDateTime changesRequestedAt,
                           String qualityTagLabel, OffsetDateTime qualityTaggedAt) {}

    /**
     * Every SUBMITTED exercise submission across the coach's visible founders,
     * oldest first — the review queue. A non-null {@code changes_requested_at}
     * on a SUBMITTED row = this copy came back after "request changes" (the
     * stamp is write-once history; {@code reviewed_at} can't carry the signal
     * because both the resubmit and requestChanges null it).
     */
    public List<QueueRow> reviewQueue(UUID orgId, UUID coachId) {
        return jdbc.query("""
                SELECT ea.id AS assignment_id, u.id AS founder_id, u.name AS founder_name,
                       et.name AS exercise_name, es.submitted_at, es.changes_requested_at,
                       es.quality_tag_label, es.quality_tagged_at
                FROM exercise_assignments ea
                JOIN exercise_templates et ON et.id = ea.template_id
                JOIN exercise_submissions es ON es.assignment_id = ea.id
                JOIN users u ON u.id = ea.user_id
                WHERE ea.organization_id = :orgId
                  AND es.status = 'SUBMITTED'
                  AND %s
                ORDER BY es.submitted_at ASC NULLS LAST, ea.id
                """.formatted(VISIBLE_FOUNDER),
                params(orgId, coachId),
                (rs, i) -> new QueueRow(rs.getObject("assignment_id", UUID.class),
                        rs.getObject("founder_id", UUID.class), rs.getString("founder_name"),
                        rs.getString("exercise_name"),
                        rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("changes_requested_at", OffsetDateTime.class),
                        rs.getString("quality_tag_label"),
                        rs.getObject("quality_tagged_at", OffsetDateTime.class)));
    }

    public record CoachOfMemberRow(UUID id, String name, String headline, String bio,
                                   String photoUrl, String bookingUrl) {}

    /**
     * The coach card a founder reads: who they are (V178 headline/bio/photo) and
     * the link they published, over the ACTIVE COACHES of one tenant.
     *
     * <p>The three predicates in this WHERE clause are the ones that hold in
     * BOTH directions of the founder-side read, so they live here rather than in
     * either caller — a fourth surface that forgets one of them is the leak:
     *
     * <ul>
     *   <li>{@code cu.organization_id = :orgId} — the tenant floor, and
     *       load-bearing on its own even where the assignment relation is also
     *       applied (see {@link #coachesOfMember});</li>
     *   <li>{@code role = 'COACH'} and {@code status = 'ACTIVE'} — the two the
     *       shared {@link CoachAccess} fragment deliberately says nothing about,
     *       because forwards the coach is the authenticated caller. Without them
     *       a suspended coach, or one demoted out of the role while a stale
     *       grant survives, is still offered to a founder as bookable.</li>
     * </ul>
     *
     * <p>{@code photo_url} comes out RAW ({@code minio://…}); resolving it is the
     * web layer's job through {@code MediaUrlPort}, which this package may not
     * reach from SQL.
     */
    private static final String COACH_CARD_SELECT = """
            SELECT cu.id, cu.name, cp.headline, cp.bio, cp.photo_url, cp.booking_url
            FROM users cu
            LEFT JOIN coach_profiles cp ON cp.coach_id = cu.id
            WHERE cu.organization_id = :orgId
              AND cu.role = 'COACH'
              AND cu.status = 'ACTIVE'
            """;

    private static final String COACH_CARD_ORDER = " ORDER BY cu.name, cu.id";

    /**
     * The reverse of {@link #roster}: every coach who may see {@code memberId},
     * with the booking link they published. Composes the SAME assignment-union
     * relation ({@link CoachAccess#VISIBLE_COACH_PREDICATE}) read backwards, so
     * a founder can never be shown a coach who cannot see them, nor miss one
     * who can.
     *
     * <p><strong>{@code cu.organization_id = :orgId} (in {@link #COACH_CARD_SELECT})
     * is load-bearing on its own.</strong> The shared relation pins the GRANT and
     * the FOUNDER to {@code :orgId}; it says nothing about the coach's org,
     * because forwards that is the caller's own. A hand-written grant naming a
     * foreign coach — {@code org_id} and {@code member_id} in org A,
     * {@code coach_id} in org B — satisfies the relation entirely, and without
     * that line the founder would be handed another tenant's coach and their
     * booking link. Covered by
     * {@code aCrossOrgGrantNeverSurfacesAForeignCoach}.
     */
    public List<CoachOfMemberRow> coachesOfMember(UUID orgId, UUID memberId) {
        return jdbc.query(
                COACH_CARD_SELECT
                        + "  AND " + CoachAccess.VISIBLE_COACH_PREDICATE.formatted("cu.id")
                        + COACH_CARD_ORDER,
                new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId),
                CoachingReadRepository::coachOfMemberRow);
    }

    /**
     * Coaches Corner: every ACTIVE COACH of the caller's own org, assigned to
     * them or not — {@link #coachesOfMember} minus the assignment relation, and
     * nothing else. The tenant floor and the role/status predicates are shared
     * verbatim ({@link #COACH_CARD_SELECT}), so widening WHO is listed cannot
     * quietly widen WHICH ORG they come from.
     *
     * <p>Dropping the relation is the whole point and is not a leak: this
     * returns a colleague's name, headline, public bio, photo and the booking
     * page they chose to publish — the directory a founder needs in order to
     * pick a coach at all. Nothing here is founder data, and nothing here is
     * private to an assignment. The narrower read stays the default on the
     * endpoint precisely so no existing caller is widened by accident.
     *
     * <p>ponytail: unbounded, like {@link #roster} — an org has tens of coaches;
     * paginate when one runs hundreds.
     */
    public List<CoachOfMemberRow> coachesInOrg(UUID orgId) {
        return jdbc.query(COACH_CARD_SELECT + COACH_CARD_ORDER,
                new MapSqlParameterSource("orgId", orgId),
                CoachingReadRepository::coachOfMemberRow);
    }

    private static CoachOfMemberRow coachOfMemberRow(java.sql.ResultSet rs, int i)
            throws java.sql.SQLException {
        return new CoachOfMemberRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("headline"), rs.getString("bio"), rs.getString("photo_url"),
                rs.getString("booking_url"));
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
                                AND s2.status = 'EVALUATED'
                                AND EXISTS (SELECT 1 FROM pillar_evaluations pe2
                                            WHERE pe2.submission_id = s2.id)
                              -- Same key as the roster FRI (evaluated_at DESC,
                              -- created_at DESC over EVALUATED) so the header score
                              -- and these pillar bars describe the SAME submission.
                              ORDER BY s2.evaluated_at DESC, s2.created_at DESC
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

    /**
     * Per-module progress over the GRANTED cohorts only (audience-filtered
     * LIVE tasks). Done-state is per task type via the shared
     * {@link TaskCompletion} fragment — an EXERCISE or COURSE task counts when
     * its owning slice says the member's side is done, not only LESSONs.
     */
    public List<ModuleProgressRow> moduleProgress(UUID orgId, UUID coachId, UUID founderId) {
        return jdbc.query("""
                SELECT c.name AS cohort_name, m.name AS module_name,
                       count(*)                    AS total,
                       count(*) FILTER (WHERE %s)  AS submitted
                FROM cohort_members cm
                JOIN cohorts c          ON c.id = cm.cohort_id AND EXISTS (SELECT 1 FROM cohort_orgs cox WHERE cox.cohort_id = c.id AND cox.org_id = :orgId)
                JOIN program_modules m  ON m.cohort_id = c.id
                JOIN program_tasks t    ON t.module_id = m.id AND t.status = 'LIVE'
                WHERE cm.user_id = :founderId
                  AND %s
                  AND %s
                  AND %s
                  AND EXISTS (SELECT 1 FROM users u WHERE u.id = :founderId AND %s)
                GROUP BY c.name, c.position, m.name, m.position
                ORDER BY c.position, c.name, m.position, m.name
                """.formatted(TASK_DONE.formatted(":founderId"),
                        GRANTED_COHORT.formatted(":founderId"),
                        AUDIENCE.formatted("cm.user_id"),
                        TaskCompletion.COUNTS_FOR_USER.formatted(":founderId"),
                        VISIBLE_FOUNDER),
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
                SELECT c.id, c.name FROM cohorts c
                JOIN cohort_orgs cox ON cox.cohort_id = c.id AND cox.org_id = :orgId
                WHERE c.id IN (:ids)
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("ids", cohortIds),
                (rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static MapSqlParameterSource params(UUID orgId, UUID coachId) {
        return new MapSqlParameterSource("orgId", orgId).addValue("coachId", coachId);
    }
}
