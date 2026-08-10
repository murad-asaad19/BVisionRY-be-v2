-- Phase E review fix: a durable "this copy came back after request-changes"
-- signal. reviewed_at cannot carry it — requestChanges and the member's
-- edit-of-REVIEWED path both null it — so the review queue's resubmitted
-- marker needs its own stamp. Write-once history: set by requestChanges,
-- never cleared by resubmit or a later mark-reviewed.
ALTER TABLE exercise_submissions
    ADD COLUMN changes_requested_at TIMESTAMP WITH TIME ZONE;
