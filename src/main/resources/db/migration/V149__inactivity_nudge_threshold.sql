-- =============================================================================
-- V149__inactivity_nudge_threshold.sql — per-org inactivity nudge window
-- (roadmap §7 items 7 + 18)
-- =============================================================================
-- Expand-only: ONE nullable-free column with a default, no table, no drop, no
-- rename. Everything else the nudge needs already exists.
--
-- WHY A COLUMN AND NOT A SETTINGS TABLE. There is no per-org settings store in
-- this schema — platform_settings is keyed by `key` alone (global), and
-- program_settings was re-keyed to cohort_id in V122. A key-value table for one
-- integer would be the only row in it. The precedent for a per-tenant number
-- knob is program_settings.due_soon_days: a typed column with a DEFAULT and a
-- CHECK, which is what this is.
--
-- SEMANTICS
--   · DEFAULT 14 — policy defaults.inactivity_threshold_days. Every existing
--     org therefore gets the documented behaviour with no backfill statement
--     and no NULL-means-default branch in Java.
--   · 0 disables nudges for the org outright (defaults.nudge_channels is
--     RESPECT_EXISTING_PREFERENCES; this is the ORG's off switch, the member's
--     own is the existing notification_optouts row).
--   · Upper bound 90 is not cosmetic. Send-once is enforced by asking whether
--     the recipient already has an INACTIVITY_NUDGE row in `notifications`
--     within the same window — and NotificationRetentionJob purges that table
--     at bvisionry.notifications.retention-days (default 90). A window longer
--     than retention would look "never nudged" again the moment the evidence
--     was purged, and the founder would be re-nudged early. The CHECK keeps
--     the two facts from drifting apart silently.
--
-- Sub-orgs do NOT inherit the parent's value (unlike subscription_tier). A
-- cohort's pace is a property of the cohort's own programme, and the column
-- carries the same default everywhere, so an untouched sub-org behaves
-- identically to an untouched parent without any resolution logic.
-- =============================================================================

ALTER TABLE organizations
    ADD COLUMN inactivity_nudge_days INTEGER NOT NULL DEFAULT 14;

ALTER TABLE organizations
    ADD CONSTRAINT ck_organizations_inactivity_nudge_days
        CHECK (inactivity_nudge_days BETWEEN 0 AND 90);
