-- Make the system-managed Gender question optional to answer.
--
-- Gender stays a locked, undeletable Personal-pillar field (so the AI can
-- personalise pronouns), but it is no longer required: EvaluationEngine
-- handles a missing/blank gender gracefully, so forcing respondents to
-- disclose it adds friction without value.
--
-- Idempotent: the WHERE clause skips rows already set to FALSE.
UPDATE questions
SET is_required = FALSE,
    updated_at  = NOW()
WHERE system_key = 'GENDER'
  AND is_required = TRUE;
