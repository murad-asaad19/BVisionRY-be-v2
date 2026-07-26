package com.bvisionry.common.programaccess;

/**
 * The single SQL source of truth for "is this program module assigned to this
 * user?".
 *
 * <p>A module reaches a user when it targets everyone, or targets a team they
 * belong to, or targets them by name ({@code program_modules.assign_mode} =
 * {@code ALL} / {@code TEAMS} / {@code MEMBERS}). Two reporting surfaces count
 * completion against that audience — the coach console's per-founder module
 * progress and the ROI report's cohort completion rate — and if their
 * predicates ever diverge the two will quote different completion percentages
 * for the same founder, which is the bug this constant exists to make
 * impossible.
 *
 * <p><strong>Why this lives in {@code common}.</strong> The ArchUnit ratchet
 * forbids new feature→feature imports and {@code common} may not import
 * features, so — like {@link com.bvisionry.common.coachaccess.CoachAccess} and
 * {@code common.gdpr.PersonalDataRepository} — this class depends on the schema
 * rather than on feature classes, and callers compose the fragment into their
 * own SQL instead of restating it.
 *
 * <p>ponytail: a third copy of this rule lives in
 * {@code programflow.web.ProgramRules#includes}, in Java rather than SQL,
 * because it filters already-loaded entities on the write path. It is out of
 * this ticket's manifest; collapse it into a single source the next time that
 * file is opened.
 */
public final class ProgramAudience {

    private ProgramAudience() {}

    /**
     * Module {@code m}'s audience includes the user given by {@code %1$s} (a
     * column or bind parameter supplied by the composing query). Expects the
     * caller's FROM clause to expose {@code program_modules} as {@code m}.
     */
    public static final String INCLUDES_USER = """
            (m.assign_mode = 'ALL'
             OR (m.assign_mode = 'TEAMS' AND EXISTS (
                   SELECT 1 FROM program_module_teams pmt
                   JOIN team_members tm ON tm.team_id = pmt.team_id
                   WHERE pmt.module_id = m.id AND tm.user_id = %1$s))
             OR (m.assign_mode = 'MEMBERS' AND EXISTS (
                   SELECT 1 FROM program_module_members pmm
                   WHERE pmm.module_id = m.id AND pmm.user_id = %1$s)))""";
}
