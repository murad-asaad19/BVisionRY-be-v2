-- Persist what prompt/rubric/temperature/model produced each evaluation so older
-- runs can be reproduced or audited after prompt templates change.

ALTER TABLE pillar_evaluations
    ADD COLUMN IF NOT EXISTS ai_temperature NUMERIC(3, 2),
    ADD COLUMN IF NOT EXISTS ai_system_prompt_version_id UUID,
    ADD COLUMN IF NOT EXISTS ai_rubric_snapshot TEXT,
    ADD COLUMN IF NOT EXISTS ai_evidence JSONB;

ALTER TABLE overall_summaries
    ADD COLUMN IF NOT EXISTS ai_temperature NUMERIC(3, 2),
    ADD COLUMN IF NOT EXISTS ai_system_prompt_version_id UUID,
    ADD COLUMN IF NOT EXISTS ai_summary_prompt_snapshot TEXT;
