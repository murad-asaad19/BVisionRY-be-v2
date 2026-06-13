-- Track async generation lifecycle on insight_reports so AI calls can run on a
-- background thread (like submission evaluation) and survive HTTP timeouts.
-- The row is inserted at status=GENERATING when the request arrives, then
-- flipped to COMPLETED (or FAILED) by the @Async worker once the AI call returns.

-- report_json was NOT NULL since V5; relax it so the stub row can be saved before
-- the AI call completes. Existing rows are unaffected.
ALTER TABLE insight_reports
    ALTER COLUMN report_json DROP NOT NULL;

ALTER TABLE insight_reports
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN failure_reason TEXT;

ALTER TABLE insight_reports
    ADD CONSTRAINT insight_reports_status_check
    CHECK (status IN ('GENERATING', 'COMPLETED', 'FAILED'));
