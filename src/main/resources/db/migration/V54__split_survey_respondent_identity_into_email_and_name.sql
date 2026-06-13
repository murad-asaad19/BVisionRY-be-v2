-- Splits the single respondent_identity_mode dropdown into two independent
-- per-field controls (email + name) so admins can collect either, both, or
-- neither, each as optional or required. Also adds respondent_name column on
-- survey_responses to store the submitted name.

ALTER TABLE surveys
    ADD COLUMN respondent_email_mode VARCHAR(10) NOT NULL DEFAULT 'NONE'
        CHECK (respondent_email_mode IN ('NONE', 'OPTIONAL', 'REQUIRED')),
    ADD COLUMN respondent_name_mode VARCHAR(10) NOT NULL DEFAULT 'NONE'
        CHECK (respondent_name_mode IN ('NONE', 'OPTIONAL', 'REQUIRED'));

UPDATE surveys
SET respondent_email_mode = CASE respondent_identity_mode
        WHEN 'EMAIL_OPTIONAL' THEN 'OPTIONAL'
        WHEN 'EMAIL_REQUIRED' THEN 'REQUIRED'
        ELSE 'NONE'
    END,
    respondent_name_mode = 'NONE';

ALTER TABLE surveys DROP COLUMN respondent_identity_mode;

ALTER TABLE survey_responses
    ADD COLUMN respondent_name VARCHAR(255);
