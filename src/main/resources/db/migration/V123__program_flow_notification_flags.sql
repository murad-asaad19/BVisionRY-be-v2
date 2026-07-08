-- =============================================================================
-- V123__program_flow_notification_flags.sql — send-once markers for the
-- program-flow notification job (module-unlocked push, task due-soon reminder).
-- NULL = not yet notified; the job stamps the moment it fires so restarts and
-- overlapping schedules can never double-send.
-- =============================================================================

ALTER TABLE program_modules ADD COLUMN unlock_notified_at timestamptz;
ALTER TABLE program_tasks   ADD COLUMN due_reminder_sent_at timestamptz;
