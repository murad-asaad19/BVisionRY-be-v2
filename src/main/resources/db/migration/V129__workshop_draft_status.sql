-- Workshops gain a DRAFT stage (mirrors pipelines): draft = editable, hidden
-- from teams; publishing (ACTIVE) locks the builder and is one-way. Existing
-- workshops are already live, so they stay ACTIVE; new ones start as DRAFT.
ALTER TABLE workshops DROP CONSTRAINT ck_workshops_status;
ALTER TABLE workshops ADD CONSTRAINT ck_workshops_status
    CHECK (status IN ('DRAFT', 'ACTIVE', 'FINISHED'));
ALTER TABLE workshops ALTER COLUMN status SET DEFAULT 'DRAFT';
