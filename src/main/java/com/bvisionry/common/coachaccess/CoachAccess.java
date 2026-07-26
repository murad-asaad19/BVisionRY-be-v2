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
     * The assignment-union membership predicate, one fragment for every
     * caller. Binds the named parameters {@code :orgId} and {@code :coachId};
     * {@code %1$s} is the member-id SQL expression (a column, parameter, or
     * subquery supplied by the composing query).
     */
    public static final String VISIBLE_MEMBER_PREDICATE = """
            EXISTS (
                SELECT 1
                FROM coach_assignments ca
                JOIN users mu ON mu.id = %1$s
                             AND mu.organization_id = ca.org_id
                             AND mu.role = 'MEMBER'
                             AND mu.status = 'ACTIVE'
                WHERE ca.org_id = :orgId
                  AND ca.coach_id = :coachId
                  AND (ca.member_id = mu.id
                       OR EXISTS (SELECT 1
                                  FROM cohort_members cm
                                  WHERE cm.cohort_id = ca.cohort_id
                                    AND cm.user_id = mu.id))
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
}
