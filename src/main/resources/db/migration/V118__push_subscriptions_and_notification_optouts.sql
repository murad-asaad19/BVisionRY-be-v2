-- Web-push notification channel: per-browser push subscriptions plus per-user
-- notification opt-outs.
--
-- The preference model is opt-OUT: every notification type is enabled by
-- default and a row in notification_optouts means "this user muted this type".
-- New users and newly added NotificationType values are therefore enabled
-- without any backfill.

CREATE TABLE push_subscriptions (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Push-service URL minted by the browser; unique per browser profile +
    -- service-worker registration, and the upsert key when a browser re-posts
    -- its subscription (possibly under a different signed-in account).
    endpoint   TEXT        NOT NULL UNIQUE,
    -- Client keys (base64url) for RFC 8291 payload encryption.
    p256dh     TEXT        NOT NULL,
    auth       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Fan-out reads subscriptions by recipient.
CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions (user_id);

CREATE TABLE notification_optouts (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- NotificationType enum name (e.g. ASSESSMENT_ASSIGNED).
    notification_type VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- One opt-out row per (user, type); also backs the toggle-off race.
    CONSTRAINT uq_notification_optouts_user_type UNIQUE (user_id, notification_type)
);
