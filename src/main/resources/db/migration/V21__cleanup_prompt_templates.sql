-- V21: Cleanup dead PromptTemplate columns and unused prompt types

-- Drop dead columns from prompt_templates
ALTER TABLE prompt_templates DROP COLUMN IF EXISTS version;
ALTER TABLE prompt_templates DROP COLUMN IF EXISTS is_active;
ALTER TABLE prompt_templates DROP COLUMN IF EXISTS change_notes;
ALTER TABLE prompt_templates DROP COLUMN IF EXISTS created_by;

-- Delete unused prompt template rows (OVERALL_SUMMARY and FREE_TIER_SUMMARY are now pipeline-level)
DELETE FROM prompt_templates WHERE prompt_type IN ('OVERALL_SUMMARY', 'FREE_TIER_SUMMARY');

-- Update CHECK constraint on prompt_type to only allow valid values
ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'EVALUATION_WRAPPER', 'TEAM_INSIGHT'));
