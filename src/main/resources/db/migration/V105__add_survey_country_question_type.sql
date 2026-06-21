-- Add COUNTRY to the survey question type set (powers the results map).
ALTER TABLE survey_questions DROP CONSTRAINT IF EXISTS survey_questions_type_check;
ALTER TABLE survey_questions
    ADD CONSTRAINT survey_questions_type_check
    CHECK (type IN ('SHORT_TEXT', 'MULTIPLE_CHOICE', 'LIKERT', 'NUMBER', 'SELF_RATING', 'COUNTRY'));
