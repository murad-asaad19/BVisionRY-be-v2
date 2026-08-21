package com.bvisionry.common.programaccess;

/**
 * The single SQL source of truth for "does this role count as a learner?" —
 * who can be enrolled in a cohort and who every roster-derived surface shows.
 *
 * <p>Learners are {@code MEMBER}, {@code ORG_ADMIN} and {@code SUPER_ADMIN}:
 * an admin who belongs to an org takes the program exactly like a founder
 * does (the assessment slice already lets admins take assessments via
 * {@code targetRoles}). {@code COACH} and {@code INSTRUCTOR} stay excluded —
 * they STAFF the program; a coach in {@code memberIds} would desync the raw
 * member count from every role-filtered roster surface forever. Note the
 * org-scoping every call site already applies ({@code organization_id =
 * :orgId}) is what keeps platform-wide SUPER_ADMINs without an org out.
 *
 * <p>Enrollability and roster visibility MUST use the same predicate — the
 * cohort screen's org slice, the coach console's {@code VISIBLE_FOUNDER},
 * the ROI report's roster and the enrollment guard all quote it. One site
 * widening while another stays behind is precisely the desync the old
 * hard-coded {@code role = 'MEMBER'} comments warned about, which is why the
 * literal now lives here and nowhere else. Lives in {@code common} because
 * the ArchUnit ratchet forbids feature→feature imports.
 */
public final class Learner {

    /**
     * Unqualified column predicate: {@code role IN (...)}. A compile-time
     * constant, so {@code @Query} annotations can splice it via {@code +};
     * prefix the alias at the call site ({@code "AND u." + Learner.ROLE_IN}).
     */
    public static final String ROLE_IN =
            "role IN ('MEMBER', 'ORG_ADMIN', 'SUPER_ADMIN')";

    /** Alias form for built strings: {@code Learner.ROLE_PREDICATE.formatted("u")}. */
    public static final String ROLE_PREDICATE = "%s." + ROLE_IN;

    private Learner() {
    }
}
