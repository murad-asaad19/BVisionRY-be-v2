-- Forensic trail for invitation acceptance: who clicked the email link, who
-- tried to register, and which attempts failed (and why). Without this, an
-- admin staring at a stuck PENDING invite has no way to tell "they never
-- opened it" from "they tried 5 times but the password rule rejected them."
--
-- View tracking lives on the invitation row (single bumpable counter, last
-- write wins) because we only care about "have they ever seen the form" and
-- "when did they last look." Attempt history needs row-per-event for a
-- timeline, hence the separate table. Failed-attempt rows must survive the
-- accept-flow rollback, so the writer uses REQUIRES_NEW.

ALTER TABLE invitations
    ADD COLUMN first_viewed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_viewed_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN view_count      INTEGER NOT NULL DEFAULT 0;

CREATE TABLE invitation_acceptance_attempts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invitation_id  UUID NOT NULL REFERENCES invitations(id) ON DELETE CASCADE,
    attempted_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    success        BOOLEAN NOT NULL,
    error_code     VARCHAR(64),
    error_message  VARCHAR(500),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invitation_attempts_invitation
    ON invitation_acceptance_attempts (invitation_id, attempted_at DESC);
