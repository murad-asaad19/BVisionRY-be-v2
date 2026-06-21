-- Per-section opt-in for the survey results "Live" analytics page.
-- Default false: a section appears on the live page only when explicitly enabled.
ALTER TABLE survey_pillars
    ADD COLUMN live_analytics_enabled BOOLEAN NOT NULL DEFAULT FALSE;
