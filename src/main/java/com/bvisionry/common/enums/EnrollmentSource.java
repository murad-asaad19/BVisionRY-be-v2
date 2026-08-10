package com.bvisionry.common.enums;

/**
 * Redesign spec §3: WHY a member has a course. One enrollment model, three
 * creation paths plus self.
 *
 * <p><strong>The declaration order IS the dedup precedence</strong> — DIRECT
 * beats ORG_RULE beats AI_SUGGESTED beats SELF. When several paths land on the
 * same course the member sees ONE row carrying the strongest source, so
 * "assigned to you by name" is never hidden behind "your whole org has this".
 * Nothing persists the ordinal ({@code @Enumerated(EnumType.STRING)}, and raw
 * SQL writes the name), so it is free to read as a ranking.
 *
 * <p>Lives in {@code common} because both the {@code enrollment} entity and the
 * {@code courseaccess} read model speak it and the ArchUnit ratchet forbids a
 * feature→feature import. Column: {@code enrollment.source} (V168).
 */
public enum EnrollmentSource {

    /** An admin assigned it to this member by name (or to a selected set). */
    DIRECT,

    /** An {@code org_course_rules} row covers the member's organization. */
    ORG_RULE,

    /** A pillar band asked for it after an evaluation (Suggest or Auto-assign). */
    AI_SUGGESTED,

    /** The member enrolled themselves from the Library. */
    SELF;

    /** Lower wins. */
    public int precedence() {
        return ordinal();
    }

    /** Never throws — an unrecognised stored value degrades to SELF, the weakest claim. */
    public static EnrollmentSource of(String stored) {
        for (EnrollmentSource s : values()) {
            if (s.name().equals(stored)) {
                return s;
            }
        }
        return SELF;
    }
}
