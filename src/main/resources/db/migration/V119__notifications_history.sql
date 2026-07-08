-- In-app notification history: one row per recipient per notification event,
-- written by the same dispatch path that fans out web-push (and regardless of
-- whether the user has any browser subscribed). read_at NULL = unread.

CREATE TABLE notifications (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- NotificationType enum name (e.g. MEMBER_JOINED).
    notification_type VARCHAR(64)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    body              TEXT         NOT NULL,
    -- Frontend-relative deep link (e.g. /my/assessments/<id>).
    url               TEXT         NOT NULL,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The bell's list query: newest-first per user.
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);

-- The hot unread-count badge query; partial so it stays tiny as history grows.
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
