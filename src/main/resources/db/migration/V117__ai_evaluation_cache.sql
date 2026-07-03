-- Content-hash cache for pillar evaluations (kills duplicate LLM spend on retakes and
-- full re-runs that submit identical answers).
--
-- cache_key is a SHA-256 hex over (model, temperature, system prompt, user message) joined
-- with a single ' ' separator (prevents boundary collisions between adjacent parts). Identical
-- inputs re-use the stored parsed result instead of re-billing the provider. Escalation
-- re-samples deliberately bypass the cache — they exist to gather INDEPENDENT opinions.

CREATE TABLE ai_evaluation_cache (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cache_key   VARCHAR(64)  NOT NULL UNIQUE,
    call_type   VARCHAR(40)  NOT NULL,
    model       VARCHAR(200) NOT NULL,
    result_json TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_hit_at TIMESTAMPTZ
);

-- Supports the bounded retention purge's oldest-first scan (DELETE WHERE created_at < cutoff
-- ORDER BY created_at LIMIT n), mirroring idx_ai_call_logs_called_at.
CREATE INDEX idx_ai_evaluation_cache_created_at ON ai_evaluation_cache (created_at);
