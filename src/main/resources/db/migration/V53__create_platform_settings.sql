-- Single-row key/value table for platform-wide tunable knobs.
CREATE TABLE platform_settings (
    key VARCHAR(100) PRIMARY KEY,
    value_int INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL
);

INSERT INTO platform_settings (key, value_int) VALUES
    ('attention.suspended_days', 7),
    ('attention.trial_expiry_window_days', 7),
    ('attention.trial_just_expired_window_days', 30),
    ('attention.idle_days', 14),
    ('attention.onboarding_stalled_hours', 24);
