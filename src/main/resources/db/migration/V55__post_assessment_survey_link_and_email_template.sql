-- Pipeline-paired surveys are now reached only through an authenticated,
-- submission-scoped flow. Each post-assessment survey response is bound to
-- the submission whose evaluation triggered it, so admins can see the
-- responses alongside the assessment results and a member can submit at
-- most once per assessment.

ALTER TABLE survey_responses
    ADD COLUMN submission_id UUID REFERENCES submissions(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX ux_survey_responses_survey_submission
    ON survey_responses(survey_id, submission_id)
    WHERE submission_id IS NOT NULL;

CREATE INDEX ix_survey_responses_submission
    ON survey_responses(submission_id)
    WHERE submission_id IS NOT NULL;

-- NOT VALID skips the up-front scan over existing rows and avoids holding an
-- AccessExclusiveLock for the duration of that scan; VALIDATE CONSTRAINT then
-- runs the integrity check under the weaker ShareUpdateExclusiveLock so reads
-- and writes against survey_responses keep flowing during deploy.
ALTER TABLE survey_responses
    ADD CONSTRAINT ck_post_assessment_has_user_and_submission
    CHECK (source <> 'POST_ASSESSMENT'
           OR (respondent_user_id IS NOT NULL AND submission_id IS NOT NULL))
    NOT VALID;

ALTER TABLE survey_responses
    VALIDATE CONSTRAINT ck_post_assessment_has_user_and_submission;
