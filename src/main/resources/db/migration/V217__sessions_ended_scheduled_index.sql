-- SessionAutoCompleteJob asks the same question every five minutes: which
-- task-backed SCHEDULED sessions have already ended? A partial index over
-- ends_at makes that a range scan on the handful of rows that qualify instead
-- of a full scan of `sessions`, and it grows with the backlog, not the table.
CREATE INDEX ix_sessions_ended_scheduled ON sessions (ends_at)
    WHERE booking_status = 'SCHEDULED' AND program_task_id IS NOT NULL;
