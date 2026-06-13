-- Admin-editable email templates now store field-level text rather than raw HTML.
-- The HTML skeleton lives in classpath templates/email/{key}.mustache and references
-- {{fields.*}} placeholders; the admin's per-field values are persisted here as JSONB.
--
-- html_body is kept nullable for one release in case we need to roll back to the
-- raw-HTML editor; new saves will write only field_values. Existing admin overrides
-- are cleared because their html_body content references the old templates directly
-- and is incompatible with the new field-driven skeletons.

ALTER TABLE email_templates
    ALTER COLUMN html_body DROP NOT NULL,
    ALTER COLUMN subject   DROP NOT NULL;

ALTER TABLE email_templates
    ADD COLUMN IF NOT EXISTS field_values JSONB;

DELETE FROM email_templates;
