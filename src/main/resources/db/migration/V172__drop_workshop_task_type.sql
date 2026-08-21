-- WORKSHOP is no longer a cohort task type.
--
-- A WORKSHOP task gated the sequential drip but a member who is not on a
-- workshop team could never complete it, so the cohort became permanently
-- unfinishable. The operator removed the type rather than patch the gating.
--
-- The standalone Workshops feature is untouched: workshops, workshop teams,
-- workshop exercises and workshop_task_submissions all stay. Only the ability
-- for a cohort's curriculum to REFERENCE a workshop goes away.

-- 1 ── drop the stale milestone tag on any submission pointing at a WORKSHOP
--      task. submissions.program_task_id carries no FK (cross-slice, by
--      convention), so nothing else would clear it. Only ASSESSMENT tasks are
--      ever tagged, so this is a belt-and-braces no-op in practice.
UPDATE submissions
SET program_task_id = NULL
WHERE program_task_id IN (SELECT id FROM program_tasks WHERE task_type = 'WORKSHOP');

-- 2 ── re-compact `position` in every module that holds a WORKSHOP task, BEFORE
--      deleting it, so the survivors end up 0..n-1 with no gap.
--      ProgramAdminService.createTask derives a new task's position from
--      tasks.size(), so a gap would collide with an existing row.
--      Renumbering first (rather than after the DELETE) keeps it to one pass:
--      a data-modifying CTE's deletes are not visible to the rest of the same
--      statement, so a post-delete CTE would still see the removed rows.
WITH renumbered AS (
    SELECT id,
           row_number() OVER (PARTITION BY module_id
                              ORDER BY position, created_at, id) - 1 AS new_position
    FROM program_tasks
    WHERE task_type <> 'WORKSHOP'
      AND module_id IN (SELECT module_id FROM program_tasks WHERE task_type = 'WORKSHOP')
)
UPDATE program_tasks t
SET position = r.new_position, updated_at = now()
FROM renumbered r
WHERE t.id = r.id AND t.position <> r.new_position;

-- 3 ── delete the WORKSHOP tasks. program_task_fields and program_submissions
--      both FK to program_tasks(id) ON DELETE CASCADE, so their rows go too.
DELETE FROM program_tasks WHERE task_type = 'WORKSHOP';

-- 4 ── the type is gone from the vocabulary.
ALTER TABLE program_tasks
    DROP CONSTRAINT ck_program_tasks_task_type;

ALTER TABLE program_tasks
    ADD CONSTRAINT ck_program_tasks_task_type
        CHECK (task_type IN ('LESSON', 'COURSE', 'EXERCISE', 'ASSESSMENT', 'SURVEY'));
