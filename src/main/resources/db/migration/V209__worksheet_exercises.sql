-- Worksheet exercises: a second template kind next to the sheet. A worksheet
-- is an ordered list of blocks (CONTENT prose, TEXT answer, CHECKBOXES,
-- TABLE) stored as one jsonb document on the template; member answers are one
-- jsonb map (block id -> value) on the submission. Review comments gain a
-- block anchor. Existing sheet templates are untouched (kind defaults SHEET).

ALTER TABLE exercise_templates
    ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'SHEET'
        CHECK (kind IN ('SHEET', 'WORKSHEET')),
    ADD COLUMN blocks JSONB;

ALTER TABLE exercise_submissions
    ADD COLUMN answers JSONB;

-- No FK: blocks live inside exercise_templates.blocks, so the id has no row
-- to reference. Block ids are immutable once the template is assigned (same
-- structure lock as sheet columns), so the anchor cannot be orphaned.
ALTER TABLE exercise_comments
    ADD COLUMN block_id UUID;
