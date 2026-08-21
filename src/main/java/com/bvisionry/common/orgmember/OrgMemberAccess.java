package com.bvisionry.common.orgmember;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.programaccess.Learner;

/**
 * "Is this member one of THIS org's founders?" — the re-anchor every org-scoped
 * member read owes, in one place.
 *
 * <p><strong>Why this exists.</strong> {@code @orgAccess.isInOrg(#orgId)} proves
 * the CALLER may act inside {@code orgId}. It says nothing about the
 * {@code memberId} in the same path. That was harmless while a member id could
 * only be reached through its own org's surfaces — and stopped being harmless
 * at V171, when a cohort became able to span organizations: an admin of org A
 * can now name a founder of org B in org A's own URL, and a read that trusts
 * the path hands over another tenant's data. Sibling reads already re-anchor by
 * hand ({@code founderprofile}'s {@code member} query,
 * {@code cohortview}'s {@code isOrgMember}); the staff viewers shipped on
 * 2026-08-12 did not, which is the leak this class closes for all of them.
 *
 * <p>Lives in {@code common} for the same reason as
 * {@link com.bvisionry.common.coachaccess.CoachAccess}: several features need
 * the identical predicate, the ArchUnit ratchet forbids feature→feature
 * imports, and a predicate that forks is a predicate that leaks. Depends on the
 * schema, not on any feature's types.
 *
 * <p>Not-found rather than forbidden, deliberately: a foreign member id must
 * not be distinguishable from a nonexistent one, which is the same stance
 * {@code FounderProfileService} takes.
 */
@Component
public class OrgMemberAccess {

    private final NamedParameterJdbcTemplate jdbc;

    public OrgMemberAccess(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when {@code memberId} is an ACTIVE learner row of {@code orgId}
     * ({@link Learner#ROLE_IN}) — THE one answer to "is this user a member of
     * this org" (operator decision 2026-08-15, matching {@code CoachAccess}):
     * a suspended member is not a learner, so learner surfaces 404 them like
     * they 404 a foreign id. The deliberate exceptions are the admin member
     * console (which lists every role and status on purpose) and coaching's
     * {@code userInOrg} (which resolves coaches for assignment, not learners).
     */
    public boolean isMemberOf(UUID orgId, UUID memberId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users u
                               WHERE u.id = :memberId
                                 AND u.organization_id = :orgId
                                 AND u.""" + Learner.ROLE_IN + """

                                 AND u.status = 'ACTIVE')
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId),
                Boolean.class));
    }

    /** {@link #isMemberOf} or 404 — the one-liner an org-scoped read calls first. */
    public void requireMemberOf(UUID orgId, UUID memberId) {
        if (!isMemberOf(orgId, memberId)) {
            throw new ResourceNotFoundException("Member", memberId.toString());
        }
    }
}
