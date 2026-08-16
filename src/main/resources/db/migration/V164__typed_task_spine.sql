-- =============================================================================
-- V164__typed_task_spine.sql — typed task spine (redesign spec §1, §2.1, §5)
-- =============================================================================
-- Program tasks stop being only multi-step forms and become the journey's
-- typed spine: LESSON (the existing form flow, unchanged), plus COURSE /
-- EXERCISE / ASSESSMENT / WORKSHOP / SURVEY tasks that REFERENCE the owning
-- slice's object (course, exercise template, pipeline, workshop, survey) by
-- bare uuid — no FK across slices, the established cross-feature stance.
--
-- ASSESSMENT tasks additionally carry a milestone role (spec §5): check-ins
-- are journey milestones; the BASELINE and DISTANCE milestones are what make
-- same-pipeline distance pairs resolvable (the tag on the spawned submission
-- says which milestone it answers, so "latest evaluated" guessing is gone).
-- =============================================================================

-- 1 ── typed program tasks ----------------------------------------------------

ALTER TABLE program_tasks
    ADD COLUMN task_type      varchar(20) NOT NULL DEFAULT 'LESSON',
    ADD COLUMN ref_id         uuid,
    ADD COLUMN milestone_role varchar(20);

-- Existing rows become LESSON: today's form-based tasks ARE lessons.

ALTER TABLE program_tasks
    ADD CONSTRAINT ck_program_tasks_task_type
        CHECK (task_type IN ('LESSON', 'COURSE', 'EXERCISE', 'ASSESSMENT',
                             'WORKSHOP', 'SURVEY')),
    ADD CONSTRAINT ck_program_tasks_milestone_role
        CHECK (milestone_role IS NULL
               OR milestone_role IN ('BASELINE', 'CHECKIN', 'DISTANCE'));

-- 2 ── milestone tag on assessment submissions --------------------------------
-- Bare column, no FK across slices (assessment-slice convention, same as
-- survey_responses.workshop_id): tags a submission with the journey milestone
-- task that spawned it, which is what makes same-pipeline baseline/check-in/
-- distance submissions distinguishable.

ALTER TABLE submissions
    ADD COLUMN program_task_id uuid;

CREATE INDEX idx_submissions_program_task
    ON submissions (program_task_id)
    WHERE program_task_id IS NOT NULL;

-- One submission per (member, milestone task): a milestone spawns exactly one
-- tagged submission, and this is what makes the open endpoint's
-- create-if-missing race-safe (ON CONFLICT DO NOTHING + re-select).
CREATE UNIQUE INDEX uq_submissions_user_program_task
    ON submissions (user_id, program_task_id)
    WHERE program_task_id IS NOT NULL;
