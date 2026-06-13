-- Mirrors V48 for survey answers: long multi-select option labels joined with
-- '|||' can blow past the 500-char cap, so promote selected_value to TEXT.
ALTER TABLE survey_answers
    ALTER COLUMN selected_value TYPE TEXT;
