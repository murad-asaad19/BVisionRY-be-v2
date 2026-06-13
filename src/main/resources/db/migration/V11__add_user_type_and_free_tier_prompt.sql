-- Add user_type to users
ALTER TABLE users ADD COLUMN user_type VARCHAR(20) DEFAULT 'LEADER';

-- Add FREE_TIER_SUMMARY to prompt check constraint
ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'EVALUATION_WRAPPER', 'OVERALL_SUMMARY', 'TEAM_INSIGHT', 'FREE_TIER_SUMMARY'));

-- Seed free tier prompt
INSERT INTO prompt_templates (id, prompt_type, content, version, is_active, change_notes)
VALUES (gen_random_uuid(), 'FREE_TIER_SUMMARY',
'Provide a brief, encouraging summary of the assessment responses. Highlight the top 3 strengths observed. Give a high-level maturity indication without detailed pillar scores. End with a teaser about what the full Premium report reveals.',
1, TRUE, 'Initial free tier summary prompt');
