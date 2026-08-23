package com.bvisionry.common.programaccess;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;

/**
 * The single SQL source of truth for "has this user DONE this program task?",
 * per task type of the typed spine (redesign spec §1).
 *
 * <p><strong>Done-semantics source of truth:</strong>
 * {@code programflow.web.ProgramRules} ({@code done()} + the per-type state
 * mappings). The member's side of the work is done: LESSON = SUBMITTED program
 * submission, COURSE = COMPLETED enrollment, EXERCISE = SUBMITTED, REVIEWED or
 * NOT_SUBMITTED — the operator closed that record (V208), so the journey moves
 * on as if it were submitted — (a CHANGES_REQUESTED copy is back with the
 * member and does NOT count),
 * ASSESSMENT = tagged submission submitted — or an ADOPTED sitting, below —
 * SURVEY = a tagged response exists.
 *
 * <p><strong>ASSESSMENT milestones adopt untagged sittings</strong>, mirroring
 * the member journey's rule ({@code TaskSpineRepository}'s
 * {@code ADOPTABLE_SITTINGS} — change one, change the other): when the
 * enrolled member has NO submission tagged to the task at all, a BASELINE
 * milestone counts an EVALUATED untagged sitting of the referenced pipeline
 * whenever it happened. A DISTANCE milestone's floor depends on the pair's
 * shape: on a shared instrument (the cohort's BASELINE references the same
 * pipeline) it counts a sitting evaluated after the cohort launched that is
 * not the pipeline's earliest (the one BASELINE takes; one sitting must never
 * satisfy both roles); on its own instrument it counts a sitting evaluated
 * after the member's baseline reading — the instrument itself marks it as the
 * "after" measurement, so the launch date is not the floor. CHECKIN adopts
 * nothing. Without this branch the journey showed an adopted pre-cohort intake
 * as done while every DONE_FOR_USER surface still reported it outstanding.
 *
 * <p><strong>Everything but COURSE keys on {@code t.id}</strong> (V173): the
 * owning slice's row carries the {@code program_task_id} that spawned it, so
 * two cohorts pointing at one template/pipeline/survey each track their own
 * progress. COURSE is the deliberate exception — a course is per member, one
 * global enrollment, shared by every task that references it.
 *
 * <p>Six surfaces count completion against this rule — the engagement
 * record's assignments row, the coach console's roster/module progress, the
 * cohort screen's roster/outline, the ROI report's cohort completion and the
 * due-reminder job's "already done" filter — and if their predicates ever
 * diverge, two org surfaces quote different completion numbers for the same
 * founder. Like {@link ProgramAudience}, this lives in {@code common} because
 * the ArchUnit ratchet forbids feature→feature imports: the fragment depends
 * on the schema only, and callers compose it instead of restating it.
 *
 * <p>Every completion FRACTION must also apply {@link #COUNTS_FOR_USER} — the
 * SQL twin of {@code programflow.web.ProgramRules#gates}. A COURSE task whose
 * course the member's org can no longer SEE counts in neither side of any
 * fraction (spec §3 downgrade policy); before this fragment existed, every
 * SQL surface silently included such tasks while the learner journey
 * excluded them, and org surfaces quoted lower completion than the journey.
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
                      AND des.status IN ('SUBMITTED', 'REVIEWED', 'NOT_SUBMITTED'))
                WHEN 'ASSESSMENT' THEN (EXISTS (
                    SELECT 1 FROM submissions dsu
                    WHERE dsu.program_task_id = t.id AND dsu.user_id = %1$s
                      AND dsu.submitted_at IS NOT NULL)
                 -- Adoption (TaskSpineRepository.ADOPTABLE_SITTINGS, mirrored):
                 -- a milestone with no tagged submission at all counts the
                 -- untagged sitting the journey resolves for it.
                 OR (t.milestone_role IN ('BASELINE', 'DISTANCE')
                     AND NOT EXISTS (
                        SELECT 1 FROM submissions dtg
                        WHERE dtg.program_task_id = t.id AND dtg.user_id = %1$s)
                     AND EXISTS (
                        SELECT 1 FROM submissions dad
                        JOIN assignments daa ON daa.id = dad.assignment_id
                                            AND daa.pipeline_id = t.ref_id
                        JOIN program_modules dam ON dam.id = t.module_id
                        JOIN cohorts dac ON dac.id = dam.cohort_id
                        JOIN cohort_members dacm ON dacm.cohort_id = dac.id
                                                AND dacm.user_id = %1$s
                        WHERE dad.user_id = %1$s
                          AND dad.status = 'EVALUATED'
                          AND dad.program_task_id IS NULL
                          AND (t.milestone_role = 'BASELINE'
                               -- shared instrument: launch floor + BASELINE's
                               -- earliest-sitting carve-out; own instrument:
                               -- floor is the member's baseline reading
                               -- (ADOPTABLE_SITTINGS, mirrored)
                               OR (EXISTS (SELECT 1
                                           FROM program_tasks dbt
                                           JOIN program_modules dbm ON dbm.id = dbt.module_id
                                           WHERE dbm.cohort_id = dac.id
                                             AND dbt.task_type = 'ASSESSMENT'
                                             AND dbt.milestone_role = 'BASELINE'
                                             AND dbt.ref_id = daa.pipeline_id)
                                   AND dac.launched_at IS NOT NULL
                                   AND dad.evaluated_at > dac.launched_at
                                   AND dad.id <> (SELECT dfe.id
                                                  FROM submissions dfe
                                                  JOIN assignments dfa ON dfa.id = dfe.assignment_id
                                                  WHERE dfe.user_id = dad.user_id
                                                    AND dfa.pipeline_id = daa.pipeline_id
                                                    AND dfe.status = 'EVALUATED'
                                                  ORDER BY dfe.evaluated_at, dfe.created_at
                                                  LIMIT 1))
                               OR (NOT EXISTS (SELECT 1
                                               FROM program_tasks dbt
                                               JOIN program_modules dbm ON dbm.id = dbt.module_id
                                               WHERE dbm.cohort_id = dac.id
                                                 AND dbt.task_type = 'ASSESSMENT'
                                                 AND dbt.milestone_role = 'BASELINE'
                                                 AND dbt.ref_id = daa.pipeline_id)
                                   AND dad.evaluated_at > COALESCE(
                                           (SELECT min(dbs.evaluated_at)
                                            FROM submissions dbs
                                            JOIN assignments dbsa ON dbsa.id = dbs.assignment_id
                                            JOIN program_tasks dbt ON dbt.ref_id = dbsa.pipeline_id
                                            JOIN program_modules dbm ON dbm.id = dbt.module_id
                                            WHERE dbm.cohort_id = dac.id
                                              AND dbt.task_type = 'ASSESSMENT'
                                              AND dbt.milestone_role = 'BASELINE'
                                              AND dbs.user_id = dad.user_id
                                              AND dbs.status = 'EVALUATED'),
                                           '-infinity'::timestamptz))))))
                WHEN 'SURVEY' THEN EXISTS (
                    SELECT 1 FROM survey_responses dsr
                    WHERE dsr.program_task_id = t.id
                      AND dsr.respondent_user_id = %1$s)
            END)""";

    /**
     * Boolean SQL expression: the program task the composing query exposes as
     * {@code t} counts in the completion fraction of the user given by
     * {@code %1$s} — the SQL twin of {@code ProgramRules.gates} (change one,
     * change the other; {@code TaskSpineIntegrationTest} asserts they agree).
     *
     * <p>A COURSE task whose course the user's org may not SEE
     * ({@link CourseVisibilityAccess#VISIBLE_TO_ORG}) counts in NEITHER side
     * of a fraction: apply this next to {@link #DONE_FOR_USER} wherever a
     * done/total pair is computed. A user with no organization is never gated,
     * matching {@code CourseVisibilityAccess.isVisibleToUser}. The Java rule's
     * other arm — a task type not completable in-app — has no SQL twin because
     * every current type is completable ({@code ProgramTaskType}).
     *
     * <p>The inner course alias must be {@code c} (VISIBLE_TO_ORG's contract);
     * it deliberately shadows any composing query's own {@code c} inside the
     * EXISTS only.
     */
    public static final String COUNTS_FOR_USER = """
            (t.task_type <> 'COURSE'
             OR t.ref_id IS NULL
             OR EXISTS (
                SELECT 1 FROM users dgu, course c
                WHERE dgu.id = %%1$s AND c.id = t.ref_id
                  AND (dgu.organization_id IS NULL OR %s)))"""
            .formatted(CourseVisibilityAccess.VISIBLE_TO_ORG.formatted("dgu.organization_id"));
}
