package com.bvisionry.common.enums;

import com.bvisionry.common.exception.BadRequestException;

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

    /**
     * The strict counterpart of {@link #of}, for parsing a REQUEST rather than a
     * stored column. The degrade-to-SELF above is right for data we already own —
     * a mystery row should surface weakly, not crash a read — but on a write path
     * it is a loaded gun: {@code ?source=ORG-RULE} (a typo) would silently become
     * SELF and the caller's "remove ORG-RULE enrolments" would cancel every
     * self-enrolment in the org instead. A typo is the caller's mistake, so it is
     * a 400.
     */
    public static EnrollmentSource strictOf(String requested) {
        for (EnrollmentSource s : values()) {
            if (s.name().equals(requested)) {
                return s;
            }
        }
        throw new BadRequestException("Unknown enrollment source: " + requested);
    }
}
