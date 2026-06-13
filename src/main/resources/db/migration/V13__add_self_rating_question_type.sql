-- Add SELF_RATING to question type constraint
ALTER TABLE questions DROP CONSTRAINT IF EXISTS questions_type_check;
ALTER TABLE questions ADD CONSTRAINT questions_type_check
    CHECK (type IN ('FREE_TEXT', 'LIKERT', 'MULTIPLE_CHOICE', 'SELF_RATING'));
