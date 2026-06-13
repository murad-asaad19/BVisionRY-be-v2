-- Dual-provider AI config:
--   1. Default new installs to ANTHROPIC (native prompt caching + faster token gen
--      than OpenRouter's Bedrock/Vertex fallbacks). Existing rows keep their value.
--   2. Store one encrypted key per provider so operators can pre-configure both
--      and flip providers in the admin UI without re-pasting the key. The existing
--      api_key_encrypted was provider-implicit; rename it to openrouter_* since
--      every deployed config to date was OpenRouter.
ALTER TABLE ai_configurations ALTER COLUMN provider SET DEFAULT 'ANTHROPIC';
ALTER TABLE ai_configurations RENAME COLUMN api_key_encrypted TO openrouter_api_key_encrypted;
ALTER TABLE ai_configurations ADD COLUMN anthropic_api_key_encrypted TEXT;
