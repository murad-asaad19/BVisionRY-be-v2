-- V59__refresh_tokens.sql
-- Server-side refresh-token store for rotation, revocation, and theft detection.
-- Each row represents an issued refresh JWT keyed by its `jti` claim. On
-- /api/auth/refresh the row is looked up, revoked, and replaced (rotation).
-- Replay of an already-revoked token triggers blanket revocation per user.

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jti UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    replaced_by_jti UUID NULL,
    user_agent VARCHAR(255) NULL,
    ip_hash VARCHAR(64) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
