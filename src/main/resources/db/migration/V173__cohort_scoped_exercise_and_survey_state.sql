-- =============================================================================
-- V173__cohort_scoped_exercise_and_survey_state.sql
-- A cohort owns its own EXERCISE and SURVEY progress
-- =============================================================================
-- Two cohorts may each carry a task pointing at the SAME exercise template or
-- the SAME survey. Until now completion was keyed on (member, template) and
-- (member, survey), so doing the work in cohort A silently marked cohort B's
-- task done — on the member's journey and on the admin matrix alike.
--
-- Fix: tag the owning slice's row with the program task that spawned it, the
-- same shape ASSESSMENT already uses (submissions.program_task_id, V164). Bare
-- uuid, deliberately NO foreign key — the established cross-slice convention
-- (submissions.program_task_id, survey_responses.workshop_id predate this).
--
-- COURSE is out of scope on purpose: a course is per member, so the single
-- global enrollment per (user, course) stays exactly as it is.
-- =============================================================================

-- 1 ── the tags ---------------------------------------------------------------

ALTER TABLE exercise_assignments ADD COLUMN program_task_id uuid;
ALTER TABLE survey_responses     ADD COLUMN program_task_id uuid;

-- 2 ── uniqueness has to widen ------------------------------------------------
-- Without this a member could never do the same exercise/survey in a second
-- cohort: the old keys ((org, template, member) and (survey, member)) would
-- reject the second cohort's row.

-- exercise_assignments: the direct-assignment rule ("a member gets a template
-- at most once per org") now covers UNTAGGED rows only. A cohort task gets
-- exactly one assignment per (member, task) — the mirror of
-- uq_submissions_user_program_task, and what keeps the open endpoint's
-- create-if-missing race-safe (ON CONFLICT DO NOTHING + re-select).
DROP INDEX uq_exercise_assignments_member;
CREATE UNIQUE INDEX uq_exercise_assignments_member
    ON exercise_assignments (organization_id, template_id, user_id)
    WHERE user_id IS NOT NULL AND program_task_id IS NULL;

-- Leading column is the task id: this index is also the lookup for the journey
-- and the admin matrix (program_task_id IN (…) AND user_id IN (…)).
CREATE UNIQUE INDEX uq_exercise_assignments_program_task_user
    ON exercise_assignments (program_task_id, user_id)
    WHERE program_task_id IS NOT NULL;

-- survey_responses: was one response per member per SURVEY (V165); it becomes
-- one per member per TASK. Same index-doubles-as-lookup trick.
DROP INDEX ux_survey_responses_program_task_user;
CREATE UNIQUE INDEX ux_survey_responses_program_task_user
    ON survey_responses (program_task_id, respondent_user_id)
    WHERE program_task_id IS NOT NULL;

-- 3 ── conservative backfill --------------------------------------------------
-- Existing rows carry a null tag, which would read NOT_STARTED on every cohort
-- task and throw away real progress. Tag a row only when the match is
-- UNAMBIGUOUS: exactly one LIVE cohort task of that type references its
-- template/survey in a cohort the member is enrolled in AND whose module
-- audience includes them (the same predicate the pre-V173 "covered by a cohort
-- task" rule used), and no sibling row competes for that same task. Anything
-- else keeps a null tag and shows up as direct/standalone work.

WITH candidate AS (
    SELECT ea.id                AS assignment_id,
           ea.user_id           AS user_id,
           (array_agg(t.id))[1] AS task_id,
           count(*)             AS task_count
    FROM exercise_assignments ea
    JOIN cohort_members cm  ON cm.user_id = ea.user_id
    JOIN program_modules m  ON m.cohort_id = cm.cohort_id
    JOIN program_tasks t    ON t.module_id = m.id
                           AND t.status = 'LIVE'
                           AND t.task_type = 'EXERCISE'
                           AND t.ref_id = ea.template_id
    WHERE ea.user_id IS NOT NULL
      AND (m.assign_mode = 'ALL'
           OR (m.assign_mode = 'MEMBERS' AND EXISTS (
                 SELECT 1 FROM program_module_members pmm
                 WHERE pmm.module_id = m.id AND pmm.user_id = ea.user_id)))
    GROUP BY ea.id, ea.user_id
),
unambiguous AS (
    SELECT assignment_id, user_id, task_id FROM candidate WHERE task_count = 1
),
-- Contention is per (member, task) — that is the new unique key. Different
-- members claiming the same task is the normal case, not a conflict.
uncontested AS (
    SELECT user_id, task_id FROM unambiguous
    GROUP BY user_id, task_id HAVING count(*) = 1
)
UPDATE exercise_assignments ea
SET program_task_id = u.task_id
FROM unambiguous u
JOIN uncontested c ON c.user_id = u.user_id AND c.task_id = u.task_id
WHERE ea.id = u.assignment_id;

-- Survey side: PROGRAM_TASK responses only. A public-link / post-assessment /
-- workshop-intro response was never cohort work — it merely happened to
-- satisfy the old (survey, member) key — so it keeps a null tag, exactly like
-- the flows that write it keep writing null.

WITH candidate AS (
    SELECT sr.id                AS response_id,
           sr.respondent_user_id AS user_id,
           (array_agg(t.id))[1] AS task_id,
           count(*)             AS task_count
    FROM survey_responses sr
    JOIN cohort_members cm  ON cm.user_id = sr.respondent_user_id
    JOIN program_modules m  ON m.cohort_id = cm.cohort_id
    JOIN program_tasks t    ON t.module_id = m.id
                           AND t.status = 'LIVE'
                           AND t.task_type = 'SURVEY'
                           AND t.ref_id = sr.survey_id
    WHERE sr.source = 'PROGRAM_TASK'
      AND sr.respondent_user_id IS NOT NULL
      AND (m.assign_mode = 'ALL'
           OR (m.assign_mode = 'MEMBERS' AND EXISTS (
                 SELECT 1 FROM program_module_members pmm
                 WHERE pmm.module_id = m.id AND pmm.user_id = sr.respondent_user_id)))
    GROUP BY sr.id, sr.respondent_user_id
),
unambiguous AS (
    SELECT response_id, user_id, task_id FROM candidate WHERE task_count = 1
),
uncontested AS (
    SELECT user_id, task_id FROM unambiguous
    GROUP BY user_id, task_id HAVING count(*) = 1
)
UPDATE survey_responses sr
SET program_task_id = u.task_id
FROM unambiguous u
JOIN uncontested c ON c.user_id = u.user_id AND c.task_id = u.task_id
WHERE sr.id = u.response_id;
