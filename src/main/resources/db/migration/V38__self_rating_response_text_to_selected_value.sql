-- Migrate legacy SELF_RATING answers: move response_text into selected_value.
-- SELF_RATING is numeric and must route through selected_value, matching
-- LIKERT/MULTIPLE_CHOICE/NUMBER. Earlier code briefly routed it through
-- response_text; this backfill repairs any rows written during that window.
UPDATE answers a
SET selected_value = a.response_text,
    response_text  = NULL
FROM questions q
WHERE a.question_id = q.id
  AND q.type = 'SELF_RATING'
  AND a.selected_value IS NULL
  AND a.response_text IS NOT NULL;
