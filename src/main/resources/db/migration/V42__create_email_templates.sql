-- Admin-editable email templates. Each row overrides the classpath fallback
-- at src/main/resources/templates/email/{key}.mustache. When no row exists
-- for a key, the bundled default is used. "Reset to default" from the admin
-- UI simply deletes the row.
CREATE TABLE email_templates (
    template_key    VARCHAR(64)  PRIMARY KEY,
    subject         TEXT         NOT NULL,
    html_body       TEXT         NOT NULL,
    updated_by      UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
