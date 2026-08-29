-- Optional survey shown after a public exercise is submitted: the exercise's
-- thank-you screen gets a CTA into it, the same "what next" pairing pipelines
-- and workshops already have (pipelines.post_completion_survey_id, V39/V124).
--
-- PUBLIC LINK ONLY, on purpose. A member filling this exercise inside an org
-- has a review loop to come back to, not a thank-you screen, so pairing here
-- would change a flow nobody asked to change. Anonymous respondents are the
-- ones with nowhere to go next.
--
-- ON DELETE SET NULL, like the pipeline column: deleting a survey unpairs it
-- rather than taking the exercise with it.
ALTER TABLE exercise_templates
    ADD COLUMN post_completion_survey_id UUID REFERENCES surveys(id) ON DELETE SET NULL;
