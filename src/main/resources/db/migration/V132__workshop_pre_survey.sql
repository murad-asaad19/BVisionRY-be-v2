-- Pre-workshop (intro) survey: a survey a member must complete before the
-- workshop's tasks unlock. Unlike the post-completion pairing (an unverified
-- link-out on the thank-you screen), this is a VERIFIED per-member gate — the
-- response is tied to (survey, workshop, member) and play stays locked until it
-- exists.

ALTER TABLE workshops
    ADD COLUMN pre_workshop_survey_id UUID;

-- The workshop a WORKSHOP_INTRO response gates. A plain column (not an entity
-- relation) keeps the survey slice free of a survey->workshops dependency, the
-- same soft-coupling-by-id trick the rest of the workshops slice uses. Deleting
-- a workshop cascades away its intro responses.
ALTER TABLE survey_responses
    ADD COLUMN workshop_id UUID;

ALTER TABLE survey_responses
    ADD CONSTRAINT fk_survey_responses_workshop
    FOREIGN KEY (workshop_id) REFERENCES workshops(id) ON DELETE CASCADE;

-- WORKSHOP_INTRO joins the response-source vocabulary.
ALTER TABLE survey_responses
    DROP CONSTRAINT survey_responses_source_check;
ALTER TABLE survey_responses
    ADD CONSTRAINT survey_responses_source_check
    CHECK (source IN ('PUBLIC_LINK', 'POST_ASSESSMENT', 'WORKSHOP_INTRO'));

-- A workshop-intro response always carries the member + workshop it gates
-- (mirrors ck_post_assessment_has_user_and_submission).
ALTER TABLE survey_responses
    ADD CONSTRAINT ck_workshop_intro_has_user_and_workshop
    CHECK (source <> 'WORKSHOP_INTRO'
           OR (respondent_user_id IS NOT NULL AND workshop_id IS NOT NULL));

-- One intro response per member per workshop — the gate's single-submission key
-- (the real race gate behind the app-level exists check, like
-- ux_survey_responses_survey_submission).
CREATE UNIQUE INDEX ux_survey_responses_workshop_user
    ON survey_responses (survey_id, workshop_id, respondent_user_id)
    WHERE workshop_id IS NOT NULL;
