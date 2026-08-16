-- =============================================================================
-- V187__program_task_pillars.sql — "this board task grows this pillar"
-- (redesign spec §1). Modeled on pillar_course_mappings (V150): a plain join
-- table, config only, written by nothing but the Curriculum builder's Save.
-- =============================================================================
-- THE TAG IS THE DISTANCE PILLAR ID. Narratives are keyed per cohort by
-- distance_pillar_id, and comparison_pillar_mappings pairs baseline↔distance
-- per cohort — so storing the distance side makes a tag line up 1:1 with the
-- narrative it feeds, with no second resolution step. The save validates each
-- id against the cohort's own mapped distance pillars; the schema cannot
-- express that (the mapping is cohort-scoped, the task only knows its module),
-- so there is no CHECK here to back it up.
--
-- Both FKs cascade, for the same reason V150's do: the row is a statement
-- about two things that exist, and must not outlive either. Deleting a task
-- takes its tags; deleting a pillar takes every tag pointing at it.
--
-- Tagging is OPTIONAL and retroactive-safe: no task is required to carry a
-- pillar, and changing tags moves nothing on its own — narratives pick the new
-- tags up when an admin regenerates them.
-- =============================================================================

CREATE TABLE program_task_pillars (
    task_id   uuid NOT NULL REFERENCES program_tasks (id) ON DELETE CASCADE,
    pillar_id uuid NOT NULL REFERENCES pillars (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, pillar_id)
);

-- The narrative's read: "which tasks feed this pillar". The PK already serves
-- the task-side read and the task FK's cascade.
CREATE INDEX ix_ptp_pillar ON program_task_pillars (pillar_id);
