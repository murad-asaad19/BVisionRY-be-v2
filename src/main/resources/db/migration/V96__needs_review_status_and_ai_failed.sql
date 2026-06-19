-- Fail-loud evaluation outcomes.
--
-- Before: any submission whose AI evaluation finished was marked EVALUATED, even
-- when a pillar or the overall summary could not be parsed — it persisted as a
-- zero-score row indistinguishable from a legitimate zero. A model regression
-- looked identical to a healthy run.
--
-- After: when one or more pillars (or the summary) fail to produce valid output
-- even after the engine's repair retries, the submission is marked NEEDS_REVIEW
-- (partial results are still saved and visible to admins, who can retry), and the
-- specific pillars that failed are flagged via pillar_evaluations.ai_failed.

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS submissions_status_check;
ALTER TABLE submissions ADD CONSTRAINT submissions_status_check
    CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EVALUATED', 'FAILED', 'PENDING_REEDIT', 'NEEDS_REVIEW'));

ALTER TABLE pillar_evaluations ADD COLUMN ai_failed BOOLEAN NOT NULL DEFAULT FALSE;
