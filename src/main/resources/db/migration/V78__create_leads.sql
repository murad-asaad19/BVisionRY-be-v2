-- V78__create_leads.sql
-- Stores Book-a-Demo leads captured via the marketing modal.
-- Append-only: no updated_at column.

CREATE TABLE leads (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    organization  VARCHAR(255) NOT NULL,
    role          VARCHAR(255) NOT NULL,
    program_type  VARCHAR(255) NOT NULL,
    cohort_size   VARCHAR(64),
    message       TEXT        NOT NULL,
    source        VARCHAR(128),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_leads_created_at ON leads (created_at DESC);
CREATE INDEX idx_leads_email      ON leads (email);
