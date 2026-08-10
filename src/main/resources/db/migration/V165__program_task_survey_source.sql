-- Member survey-response route for SURVEY journey tasks (redesign spec §2.1,
-- phase D2). A member completes a cohort SURVEY task in-app; the response is
-- tied to (survey, member) with a dedicated source so the origin is auditable
-- and the single-submission gate has a real race guard.

-- PROGRAM_TASK joins the response-source vocabulary.
ALTER TABLE survey_responses
    DROP CONSTRAINT survey_responses_source_check;
ALTER TABLE survey_responses
    ADD CONSTRAINT survey_responses_source_check
    CHECK (source IN ('PUBLIC_LINK', 'POST_ASSESSMENT', 'WORKSHOP_INTRO', 'PROGRAM_TASK'));

-- A program-task response always carries the member it belongs to (mirrors
-- ck_workshop_intro_has_user_and_workshop).
ALTER TABLE survey_responses
    ADD CONSTRAINT ck_program_task_has_user
    CHECK (source <> 'PROGRAM_TASK' OR respondent_user_id IS NOT NULL);

-- One program-task response per member per survey — the real race gate behind
-- the app-level exists check (like ux_survey_responses_workshop_user). Keyed on
-- (survey, member) without a task id on purpose: the journey's done-detection
-- (TaskSpineRepository.surveyParticipation) keys on exactly that pair, so two
-- tasks referencing the same survey are both satisfied by the one response.
CREATE UNIQUE INDEX ux_survey_responses_program_task_user
    ON survey_responses (survey_id, respondent_user_id)
    WHERE source = 'PROGRAM_TASK';
