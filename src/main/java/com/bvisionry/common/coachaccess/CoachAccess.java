package com.bvisionry.common.coachaccess;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single source of truth for "may this coach see this member?".
 *
 * <p>A coach's visibility is the UNION of their {@code coach_assignments} rows
 * (policy {@code coach_assignment_grain: COHORT_AND_DIRECT}): every member of a
 * cohort the coach is assigned to, plus every member assigned directly. The
 * member must be an ACTIVE MEMBER of the assignment's organization — a member
 * moved to another sub-org, suspended, or deactivated drops out of visibility
 * even if a stale grant remains.
 *
 * <p><strong>Why this lives in {@code common}.</strong> Two features must agree
 * on the predicate or the divergence IS the leak: the {@code coaching} console
 * (roster / founder detail) and any feature authorizing a coach action on a
 * member's artefact (today: {@code exercise}'s submission review). The ArchUnit
 * ratchet forbids new feature→feature imports and {@code common} may not import
 * features, so — like {@code common.gdpr.PersonalDataRepository} — this class
 * depends on the schema, not on feature classes. Callers compose
 * {@link #VISIBLE_MEMBER_PREDICATE} into their own SQL rather than restating
 * it, so the union logic cannot fork.
 */
@Component
public class CoachAccess {

    /**
     * The relation itself, with BOTH sides left as SQL expressions: {@code %1$s}
     * is the member id, {@code %2$s} the coach id. Every predicate below is this
     * one template with one side pinned — so "which founders may this coach see"
     * and "which coaches see this founder" are the same rule read in two
     * directions, and cannot fork.
     */
    private static final String RELATION = """
            EXISTS (
                SELECT 1
                FROM coach_assignments ca
                JOIN users mu ON mu.id = %1$s
                             AND mu.organization_id = ca.org_id
                             AND mu.role = 'MEMBER'
                             AND mu.status = 'ACTIVE'
                WHERE ca.org_id = :orgId
                  AND ca.coach_id = %2$s
                  AND (ca.member_id = mu.id
                       OR EXISTS (SELECT 1
                                  FROM cohort_members cm
                                  WHERE cm.cohort_id = ca.cohort_id
                                    AND cm.user_id = mu.id))
            )""";

    /**
     * The assignment-union membership predicate, one fragment for every
     * caller. Binds the named parameters {@code :orgId} and {@code :coachId};
     * {@code %1$s} is the member-id SQL expression (a column, parameter, or
     * subquery supplied by the composing query).
     */
    public static final String VISIBLE_MEMBER_PREDICATE = RELATION.formatted("%1$s", ":coachId");

    /**
     * The SAME predicate read backwards: which coaches see the member bound to
     * {@code :memberId}. Binds {@code :orgId} and {@code :memberId};
     * {@code %1$s} is the COACH-id SQL expression supplied by the composing
     * query (typically the outer query's {@code users} alias).
     *
     * <p>Note what this fragment does NOT assert: that the coach row is an
     * ACTIVE COACH. The forward direction gets that from authentication — the
     * coach is the caller. Here the coach is DATA, so a composing query must
     * add the role/status predicate on its own alias; {@code CoachingReadRepository}
     * does exactly that.
     */
    public static final String VISIBLE_COACH_PREDICATE = RELATION.formatted(":memberId", "%1$s");

    /**
     * The COHORT-grain grant predicate: the coach holds a whole-cohort grant on
     * cohort {@code %1$s}, and that cohort really belongs to the granting org.
     * Binds {@code :orgId} and {@code :coachId}.
     *
     * <p>Distinct question from {@link #VISIBLE_MEMBER_PREDICATE}: "may this
     * coach act on this COHORT?", not "may this coach see this MEMBER?". A
     * direct founder grant deliberately does NOT qualify — it grants sight of
     * one founder, never the right to address a cohort they happen to be in.
     *
     * <p>Aliases are prefixed {@code g*} on purpose: a composing query that
     * selects {@code FROM cohorts c} would otherwise have its own alias shadowed
     * by this fragment's, and the predicate would silently degrade to "the coach
     * holds ANY cohort grant".
     */
    public static final String GRANTED_COHORT_PREDICATE = """
            EXISTS (
                SELECT 1
                FROM coach_assignments gca
                JOIN cohorts gcc ON gcc.id = gca.cohort_id
                                AND gcc.org_id = gca.org_id
                WHERE gca.org_id = :orgId
                  AND gca.coach_id = :coachId
                  AND gca.cohort_id = %1$s
            )""";

    private final NamedParameterJdbcTemplate jdbc;

    public CoachAccess(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when {@code memberId} is inside {@code coachId}'s assignment union
     * within {@code orgId}. False on any null — an org-less coach sees nobody.
     */
    public boolean coachSees(UUID orgId, UUID coachId, UUID memberId) {
        if (orgId == null || coachId == null || memberId == null) {
            return false;
        }
        Boolean visible = jdbc.queryForObject(
                "SELECT " + VISIBLE_MEMBER_PREDICATE.formatted(":memberId"),
                new MapSqlParameterSource("orgId", orgId)
                        .addValue("coachId", coachId)
                        .addValue("memberId", memberId),
                Boolean.class);
        return Boolean.TRUE.equals(visible);
    }

    /**
     * True when {@code coachId} holds a whole-cohort grant on {@code cohortId}
     * within {@code orgId} — the authorization for acting ON a cohort (today:
     * broadcasting an announcement to it). False on any null.
     */
    public boolean coachHoldsCohort(UUID orgId, UUID coachId, UUID cohortId) {
        if (orgId == null || coachId == null || cohortId == null) {
            return false;
        }
        Boolean granted = jdbc.queryForObject(
                "SELECT " + GRANTED_COHORT_PREDICATE.formatted(":cohortId"),
                new MapSqlParameterSource("orgId", orgId)
                        .addValue("coachId", coachId)
                        .addValue("cohortId", cohortId),
                Boolean.class);
        return Boolean.TRUE.equals(granted);
    }
}
