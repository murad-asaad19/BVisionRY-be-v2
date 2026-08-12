package com.bvisionry.common.programaccess;

/**
 * The single SQL source of truth for "has this user DONE this program task?",
 * per task type of the typed spine (redesign spec §1).
 *
 * <p><strong>Done-semantics source of truth:</strong>
 * {@code programflow.web.ProgramRules} ({@code done()} + the per-type state
 * mappings). The member's side of the work is done: LESSON = SUBMITTED program
 * submission, COURSE = COMPLETED enrollment, EXERCISE = SUBMITTED or REVIEWED
 * (a CHANGES_REQUESTED copy is back with the member and does NOT count),
 * ASSESSMENT = tagged submission submitted, SURVEY = a tagged response exists.
 *
 * <p><strong>Everything but COURSE keys on {@code t.id}</strong> (V173): the
 * owning slice's row carries the {@code program_task_id} that spawned it, so
 * two cohorts pointing at one template/pipeline/survey each track their own
 * progress. COURSE is the deliberate exception — a course is per member, one
 * global enrollment, shared by every task that references it.
 *
 * <p>Four surfaces count completion against this rule — the engagement
 * record's assignments row, the coach console's roster/module progress, the
 * ROI report's cohort completion and the due-reminder job's "already done"
 * filter — and if their predicates ever diverge, two org surfaces quote
 * different completion numbers for the same founder. Like
 * {@link ProgramAudience}, this lives in {@code common} because the ArchUnit
 * ratchet forbids feature→feature imports: the fragment depends on the schema
 * only, and callers compose it instead of restating it.
 */
public final class TaskCompletion {

    private TaskCompletion() {}

    /**
     * Boolean SQL expression: the user given by {@code %1$s} (a column or bind
     * parameter supplied by the composing query) has done the program task the
     * composing query exposes as {@code t}. Inner aliases are prefixed
     * {@code d*} so they never shadow a composing query's own.
     */
    public static final String DONE_FOR_USER = """
            (CASE t.task_type
                WHEN 'LESSON' THEN EXISTS (
                    SELECT 1 FROM program_submissions dps
                    WHERE dps.task_id = t.id AND dps.user_id = %1$s
                      AND dps.status = 'SUBMITTED')
                WHEN 'COURSE' THEN EXISTS (
                    SELECT 1 FROM enrollment de
                    WHERE de.user_id = %1$s AND de.course_id = t.ref_id
                      AND de.status = 'COMPLETED')
                WHEN 'EXERCISE' THEN EXISTS (
                    SELECT 1 FROM exercise_assignments dea
                    JOIN exercise_submissions des ON des.assignment_id = dea.id
                    WHERE dea.program_task_id = t.id AND dea.user_id = %1$s
                      AND des.status IN ('SUBMITTED', 'REVIEWED'))
                WHEN 'ASSESSMENT' THEN EXISTS (
                    SELECT 1 FROM submissions dsu
                    WHERE dsu.program_task_id = t.id AND dsu.user_id = %1$s
                      AND dsu.submitted_at IS NOT NULL)
                WHEN 'SURVEY' THEN EXISTS (
                    SELECT 1 FROM survey_responses dsr
                    WHERE dsr.program_task_id = t.id
                      AND dsr.respondent_user_id = %1$s)
            END)""";
}
