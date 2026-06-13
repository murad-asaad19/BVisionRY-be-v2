-- V27: Remove unused EVALUATION_WRAPPER prompt type

DELETE FROM prompt_templates WHERE prompt_type = 'EVALUATION_WRAPPER';

ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'TEAM_INSIGHT'));
