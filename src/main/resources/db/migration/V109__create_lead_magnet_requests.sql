-- V109__create_lead_magnet_requests.sql
-- Captures email addresses submitted via the "science behind the 11 pillars"
-- lead-magnet modal on the marketing Platform page. Append-only (no updated_at).

CREATE TABLE lead_magnet_requests (
    id         UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    email      VARCHAR(320) NOT NULL,
    source     VARCHAR(128),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_lead_magnet_requests_created_at ON lead_magnet_requests (created_at DESC);
CREATE INDEX idx_lead_magnet_requests_email      ON lead_magnet_requests (email);
