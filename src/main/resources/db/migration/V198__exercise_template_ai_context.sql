-- Staff-only context for the AI on each exercise template: what the exercise is
-- for, written by an admin in the builder and NEVER shown to members. The shift
-- narrative's ACTIVITY section includes it so the model reads a submission
-- knowing what the task was asking, instead of inferring purpose from column
-- names.
ALTER TABLE exercise_templates ADD COLUMN ai_context text;
