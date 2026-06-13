-- Add FAILED status to submissions
ALTER TABLE submissions DROP CONSTRAINT IF EXISTS submissions_status_check;
ALTER TABLE submissions ADD CONSTRAINT submissions_status_check
    CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EVALUATED', 'FAILED'));
