-- SURVEY joins the workshop task-type vocabulary: a pipeline task that renders
-- a published survey inline (config: { surveyId }). The member must submit the
-- survey to complete the task — the response reuses the WORKSHOP_INTRO
-- source/keying (survey, workshop, member), so ux_survey_responses_workshop_user
-- already enforces one response per member per survey per workshop.
ALTER TABLE workshop_exercise_tasks
    DROP CONSTRAINT ck_workshop_exercise_tasks_type;
ALTER TABLE workshop_exercise_tasks
    ADD CONSTRAINT ck_workshop_exercise_tasks_type
    CHECK (task_type IN ('SORT', 'WEIGHT', 'TOP', 'QUESTION', 'SURVEY'));
