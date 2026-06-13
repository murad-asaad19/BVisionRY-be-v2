-- Per-call audit trail for every AI request we fire. Written fire-and-forget on a
-- dedicated executor so logging never blocks or slows the evaluation pipeline.
-- Full prompt text is stored so historical runs can be reproduced or debugged.
CREATE TABLE ai_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- What kind of call: pillar-evaluation | overall-summary | team-insight
    call_type VARCHAR(50) NOT NULL,
    -- Human-readable pillar name for pillar-evaluation calls; null otherwise.
    pillar_name VARCHAR(255),

    -- Correlation — both nullable because team-insight calls aren't tied to a
    -- submission, and ad-hoc test calls may have neither.
    submission_id UUID,
    pipeline_id UUID,

    model VARCHAR(255) NOT NULL,

    called_at TIMESTAMPTZ NOT NULL,
    elapsed_ms INTEGER NOT NULL,

    system_prompt TEXT,
    user_message TEXT,
    raw_response TEXT,
    error_message TEXT,

    -- From Anthropic usage metadata. Cache fields are 0 when prompt caching
    -- didn't engage (prefix < 1024 tokens, or SYSTEM_ONLY not wired).
    input_tokens INTEGER,
    output_tokens INTEGER,
    cache_creation_tokens INTEGER,
    cache_read_tokens INTEGER,

    status VARCHAR(20) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_call_logs_submission ON ai_call_logs(submission_id);
CREATE INDEX idx_ai_call_logs_pipeline ON ai_call_logs(pipeline_id);
CREATE INDEX idx_ai_call_logs_called_at ON ai_call_logs(called_at DESC);
