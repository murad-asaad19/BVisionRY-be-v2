-- Clean up obsolete GENDER answers left over from before V32.
--
-- Context: V32 narrowed the GENDER question's allowed options from
--   ['Male', 'Female', 'Other', 'Prefer not to say']
-- down to ['Male', 'Female']. The questions row was rewritten in place,
-- but existing answer rows still carry the dropped strings — those are
-- now invalid against the question's current configJson and confuse
-- the AI evaluation prompts (gendered pronoun selection in particular).
--
-- This migration NULLs `selected_value` for GENDER answers that don't
-- match the surviving option set. We don't delete the answer rows so
-- audit-of-record (who answered when) is preserved.
--
-- Idempotent: re-running is a no-op once the offending rows are cleared,
-- because the WHERE clause excludes already-NULL values.
UPDATE answers a
SET selected_value = NULL
FROM questions q
WHERE a.question_id = q.id
  AND q.system_key = 'GENDER'
  AND a.selected_value IS NOT NULL
  AND a.selected_value NOT IN ('Male', 'Female');
