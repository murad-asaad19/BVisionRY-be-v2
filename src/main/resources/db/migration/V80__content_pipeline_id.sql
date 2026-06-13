-- V80: embed an FRI assessment pipeline in a course lesson (#41).
-- ASSIGNMENT-type content can reference a pipeline; opening the lesson resolves
-- (or lazily creates) the member's assignment/submission for that pipeline.
-- Soft reference (no FK) to stay consistent with the catalog's decoupling from
-- the assessment/identity domains (cf. course.instructor_id, course.org_id).

ALTER TABLE content ADD COLUMN pipeline_id uuid;

CREATE INDEX ix_content_pipeline ON content (pipeline_id);

COMMENT ON COLUMN content.pipeline_id IS
    'For ASSIGNMENT lessons: embedded FRI pipeline (soft reference, no FK).';
