-- Track which admin most recently edited a rule, separately from the
-- original creator. Without this, every deadline / scope tweak via
-- PipelineAutoAssignmentService.upsertRule overwrote created_by, so the
-- original author was lost on the first update.
ALTER TABLE pipeline_auto_assignments
    ADD COLUMN updated_by UUID REFERENCES users(id);
