-- Allow the new PENDING_REEDIT value on submissions.status. The check
-- constraint was last set in V9 (which added FAILED) and didn't know about
-- this state; without this migration, the unlock-pillars admin action fails
-- on the EVALUATED → PENDING_REEDIT transition with submissions_status_check.
ALTER TABLE submissions DROP CONSTRAINT IF EXISTS submissions_status_check;
ALTER TABLE submissions ADD CONSTRAINT submissions_status_check
    CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EVALUATED', 'FAILED', 'PENDING_REEDIT'));
