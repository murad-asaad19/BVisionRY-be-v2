-- Normalize the AI provider column to OPENROUTER.
--
-- The rebuilt engine transport always routes through OpenRouter's OpenAI-compatible
-- endpoint and sends the configured key as an OpenRouter Bearer token. A row left at
-- provider='ANTHROPIC' (the old default) would, under the previous key-resolution,
-- ship the Anthropic key to OpenRouter and 401. The engine now reads the OpenRouter
-- key slot unconditionally, but we still normalize the column so the admin UI and the
-- AIModelCatalogService (which branches on this column for the upstream /models call)
-- reflect the only provider the transport actually uses.
--
-- The Anthropic key slot (anthropic_api_key_encrypted) is intentionally left intact so
-- no credential is lost; only the active-provider selector is corrected.

UPDATE ai_configurations
SET provider = 'OPENROUTER'
WHERE provider = 'ANTHROPIC';
