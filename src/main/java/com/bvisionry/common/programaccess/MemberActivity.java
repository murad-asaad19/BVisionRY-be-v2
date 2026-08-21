package com.bvisionry.common.programaccess;

/**
 * The single SQL source of truth for a member's LAST-ACTIVITY instant — their
 * own footprint only (login, program autosave/submit, exercise save/submit,
 * assessment start/submit, course enrol/complete); coach/admin review events
 * deliberately don't count as the member being "seen".
 *
 * <p>Promoted to {@code common} on its third consumer (the cohort-board
 * matrix), exactly as the previous two copies' comments said to: the coach
 * roster, the founder profile header and the board's "last seen" column must
 * never disagree about when a founder was last active. Lives here for the same
 * ArchUnit reason as {@link TaskCompletion}: raw-SQL read slices cannot share
 * a fragment across feature lines any other way.
 */
public final class MemberActivity {

    private MemberActivity() {}

    /**
     * SQL expression for the last-activity instant of the user row aliased
     * {@code %1$s} in the composing query. GREATEST/MAX ignore NULLs in
     * Postgres; the whole expression is NULL only for a member with no
     * footprint at all.
     */
    public static final String LAST_ACTIVITY = """
            GREATEST(
                %1$s.last_login_at,
                (SELECT max(GREATEST(aps.saved_at, aps.submitted_at))
                   FROM program_submissions aps WHERE aps.user_id = %1$s.id),
                (SELECT max(GREATEST(aes.last_saved_at, aes.submitted_at))
                   FROM exercise_submissions aes WHERE aes.user_id = %1$s.id),
                (SELECT max(GREATEST(asu.started_at, asu.submitted_at))
                   FROM submissions asu WHERE asu.user_id = %1$s.id),
                (SELECT max(GREATEST(aen.enrolled_at, aen.completed_at))
                   FROM enrollment aen WHERE aen.user_id = %1$s.id)
            )""";
}
