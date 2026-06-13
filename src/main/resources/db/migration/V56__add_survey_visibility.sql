-- Adds an explicit visibility flag to surveys so admins can lock down a survey
-- to authenticated, in-system users only.
--
--  - PRIVATE: only reachable via the authenticated post-assessment path. The
--    public-by-token endpoint must 404 for these surveys.
--  - PUBLIC : reachable via both the public link and the authenticated path
--    (current behavior pre-V56).
--
-- Column is created with DEFAULT 'PUBLIC' so existing rows preserve their
-- current behavior. Default is then flipped to 'PRIVATE' so new surveys are
-- private by default — admins must opt in to a shareable public link.

ALTER TABLE surveys
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'
        CHECK (visibility IN ('PRIVATE', 'PUBLIC'));

ALTER TABLE surveys
    ALTER COLUMN visibility SET DEFAULT 'PRIVATE';
