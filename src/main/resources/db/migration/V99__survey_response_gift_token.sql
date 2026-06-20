-- Ties a public survey response to the gift assessment it was emailed, so the
-- admin "View results" action resolves to THIS respondent's submission instead
-- of guessing by shared email.
--
-- gift_token travels in the gift email's /a/<link>?g=<token> URL; when the
-- respondent starts the gifted assessment we look the response up by token and
-- stamp the resulting submission onto gift_submission_id.
--
-- gift_submission_id is a SEPARATE column from submission_id (the post-assessment
-- link) and is ON DELETE SET NULL on purpose: for a gift the survey response is
-- the primary artifact and must survive deletion of the gifted submission
-- (submission_id cascades the response away; gift_submission_id must only detach).
-- gift_token is nullable (most responses never receive a gift) and UNIQUE so the
-- lookup resolves at most one row.
ALTER TABLE survey_responses
    ADD COLUMN gift_token UUID,
    ADD COLUMN gift_submission_id UUID REFERENCES submissions(id) ON DELETE SET NULL;

ALTER TABLE survey_responses
    ADD CONSTRAINT ux_survey_responses_gift_token UNIQUE (gift_token);

CREATE INDEX ix_survey_responses_gift_submission_id
    ON survey_responses(gift_submission_id)
    WHERE gift_submission_id IS NOT NULL;
