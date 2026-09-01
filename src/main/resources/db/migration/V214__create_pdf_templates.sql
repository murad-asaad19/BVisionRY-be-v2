-- Admin-editable PDF templates, mirroring email_templates: one row per
-- template key, present ONLY when an admin has customized something.
-- Defaults live in code (PdfTemplateSchemaRegistry), so changing a shipped
-- default flows through automatically to every non-customized template.
-- field_values holds only the fields that differ from their defaults.
CREATE TABLE pdf_templates (
    template_key    VARCHAR(64)  PRIMARY KEY,
    field_values    JSONB,
    updated_by      UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
