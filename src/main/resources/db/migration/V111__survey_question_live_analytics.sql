-- Move the results "Live" analytics opt-in from the section to the question.
-- Default false: a question appears on the live page only when explicitly enabled.
ALTER TABLE survey_questions
    ADD COLUMN live_analytics_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve existing selections: a question was "live" if its section was opted
-- in, so carry the section flag down to each of its questions.
UPDATE survey_questions q
SET live_analytics_enabled = TRUE
FROM survey_pillars p
WHERE q.pillar_id = p.id
  AND p.live_analytics_enabled = TRUE;

-- The flag now lives on the question; drop the section-level column.
ALTER TABLE survey_pillars
    DROP COLUMN live_analytics_enabled;
