-- Supports EvaluationReaper's stranded-submission recovery scan + backlog gauge
-- (findStrandedSubmittedIds / countStrandedSubmitted), which filter submissions
-- by status = 'SUBMITTED' ordered by submitted_at.
--
-- Partial index: only rows currently in SUBMITTED are indexed, so it stays tiny
-- (just the in-flight working set) even as the submissions table grows into the
-- millions at launch. Plain (non-CONCURRENT) CREATE INDEX is safe here because it
-- runs pre-launch on an effectively empty table; building it concurrently would
-- require taking the migration out of Flyway's transaction, which isn't warranted
-- for an instant build.
CREATE INDEX IF NOT EXISTS idx_submissions_submitted_status
    ON submissions (submitted_at)
    WHERE status = 'SUBMITTED';

-- Supports PublicSubmissionReaper's abandoned-session sweep, which scans public
-- submissions left IN_PROGRESS past a TTL. Partial index keeps it to just the
-- open working set even as the table grows.
CREATE INDEX IF NOT EXISTS idx_submissions_inprogress_created
    ON submissions (created_at)
    WHERE status = 'IN_PROGRESS';
