-- Survey question type set: SHORT_TEXT, MULTIPLE_CHOICE, LIKERT, NUMBER.
-- Feature is not live — no legacy data to preserve. Wipe survey draft + response
-- data and narrow the CHECK constraint to the final set.

DELETE FROM survey_answers;
DELETE FROM survey_responses;
DELETE FROM survey_questions;
DELETE FROM survey_pillars;

ALTER TABLE survey_questions DROP CONSTRAINT IF EXISTS survey_questions_type_check;
ALTER TABLE survey_questions
    ADD CONSTRAINT survey_questions_type_check
    CHECK (type IN ('SHORT_TEXT', 'MULTIPLE_CHOICE', 'LIKERT', 'NUMBER'));
