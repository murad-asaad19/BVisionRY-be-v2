-- Drop the "recommended actions" field from the assessment overall-summary pipeline.
-- Recommendations were removed from the AI output contract (lighter evaluation) and
-- from the results UI; the column is no longer mapped by OverallSummary /
-- OverallSummaryHistory. Team- and org-insight recommendations are a separate
-- feature with their own columns and are unaffected.
ALTER TABLE overall_summaries DROP COLUMN IF EXISTS recommendations;
ALTER TABLE overall_summary_history DROP COLUMN IF EXISTS recommendations;
