package com.bvisionry.common.coachaccess;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single source of truth for "may this coach see this member?".
 *
 * <p>A coach's visibility is the UNION of their {@code coach_assignments} rows
 * (policy {@code coach_assignment_grain: COHORT_AND_DIRECT_AND_ORG}): every
 * member of a cohort the coach is assigned to, plus every member assigned
 * directly, plus — when the coach holds an ORG-WIDE grant (V176: both
 * {@code cohort_id} and {@code member_id} null) — every member of that org. The
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
     *
     * <p>The third arm ({@code cohort_id IS NULL AND member_id IS NULL}) is the
     * org-wide grain. It carries no predicate of its own because the join
     * already supplies one: {@code mu} is pinned to an ACTIVE MEMBER of
     * {@code ca.org_id}, and {@code ca.org_id = :orgId}. "Every member" is
     * therefore every member OF THIS ORG — the arm cannot widen past the
     * tenant.
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
                                    AND cm.user_id = mu.id)
                       OR (ca.cohort_id IS NULL AND ca.member_id IS NULL))
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
     * Nor does an ORG-WIDE grant (V176): sight of everyone is not the right to
     * address a cohort, and the equality {@code gca.cohort_id = %1$s} is NULL —
     * hence false — on such a row, so this predicate needed no change.
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
                JOIN cohort_orgs gcc ON gcc.cohort_id = gca.cohort_id
                                    AND gcc.org_id = gca.org_id
                WHERE gca.org_id = :orgId
                  AND gca.coach_id = :coachId
                  AND gca.cohort_id = %1$s
            )""";

    private final NamedParameterJdbcTemplate jdbc;
    private final com.bvisionry.common.security.CurrentUserAccessor currentUser;

    public CoachAccess(NamedParameterJdbcTemplate jdbc,
            com.bvisionry.common.security.CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /**
     * The proven "who is viewing whom" triple {@link #requireSees} returns —
     * the token that stops a caller passing the viewer's id where the
     * founder's belongs.
     */
    public record ViewedFounder(UUID orgId, UUID founderId, UUID viewerId) {}

    /**
     * The coach viewing door — resolves the CALLING coach and proves they see
     * {@code founderId}, or 404s (a founder outside the coach's grants must be
     * indistinguishable from a nonexistent one). Replaces the four-line
     * resolve + {@link #coachSees} + throw ritual that was hand-copied at nine
     * sites across six features.
     */
    // ponytail: services still take (orgId, founderId) rather than the token;
    // move them to ViewedFounder on touch and the CALLERS-AUTHORIZE-FIRST
    // precondition becomes unrepresentable instead of documented.
    public ViewedFounder requireSees(UUID founderId) {
        com.bvisionry.common.security.CurrentUser coach = currentUser.require();
        if (!coachSees(coach.orgId(), coach.userId(), founderId)) {
            throw new com.bvisionry.common.exception.ResourceNotFoundException(
                    "Founder", String.valueOf(founderId));
        }
        return new ViewedFounder(coach.orgId(), founderId, coach.userId());
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
