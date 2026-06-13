-- Drop the pre-provenance prompt_version_id columns.
-- Replaced by ai_system_prompt_version_id (added in V36) on pillar_evaluations and
-- overall_summaries, which is populated from the Provenance record returned by
-- OpenRouterChatService. On insight_reports the column was defined in V5 but never
-- populated by any code path, so this just removes dead schema.

ALTER TABLE pillar_evaluations DROP COLUMN IF EXISTS prompt_version_id;
ALTER TABLE overall_summaries  DROP COLUMN IF EXISTS prompt_version_id;
ALTER TABLE insight_reports    DROP COLUMN IF EXISTS prompt_version_id;
