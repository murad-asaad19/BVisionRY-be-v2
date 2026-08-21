-- =============================================================================
-- V148__announcements.sql — cohort announcements (roadmap §7 item 20)
-- =============================================================================
-- Expand-only. One table; announcements are a ONE-WAY broadcast to a cohort
-- (policy decisions.communications: ANNOUNCEMENTS_ONLY, announcement_targets:
-- [cohort]) — no threads, no replies, no DMs, so there is no parent_id, no
-- recipient table and no read-receipt table to model.
--
--   · org_id is DENORMALISED next to cohort_id, exactly as coach_assignments
--     does in V147: every tenancy predicate then carries the org equality
--     without a join, and a row belonging to one org while naming another
--     org's cohort is structurally absurd rather than merely unqueried.
--   · body is PLAIN TEXT (policy announcement_body: PLAIN_TEXT_PLUS_LINKS).
--     A body carrying markup or HTML codes is REFUSED on input (the OWASP
--     sanitiser the email renderer already uses decides what counts as plain
--     text); URLs are auto-linked at RENDER time, never stored as HTML.
--   · flagged_at / flagged_by are the whole "reportable" surface: the FIRST
--     member report flips the flag so an org admin can see it, and every
--     report also writes an audit row. Deliberately not a moderation
--     workflow — no queue, no states, no per-reporter table.
--
-- Delivery is NOT modelled here: recipients receive the post through the
-- EXISTING notification pipeline (notifications + notification_optouts, whose
-- notification_type is a plain VARCHAR with no CHECK — a new enum value needs
-- no migration and no backfill, absence of an opt-out row means enabled).
--
-- Erasure/FK design (PersonalDataCoverageTest):
--   author_id / flagged_by SET NULL — the post is the ORGANIZATION's record of
--   what was broadcast to a cohort; it outlives the author's own erasure with
--   the attribution dropped, like assignments.assigned_by and join_links
--   .created_by. org_id / cohort_id CASCADE: delete the cohort and its
--   broadcasts have nothing to belong to.
-- =============================================================================

CREATE TABLE announcements (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL,
    cohort_id  uuid        NOT NULL,
    author_id  uuid,
    body       text        NOT NULL,
    flagged_at timestamptz,
    flagged_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_announcements PRIMARY KEY (id),
    CONSTRAINT fk_announcements_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_announcements_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_announcements_author FOREIGN KEY (author_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_announcements_flagged_by FOREIGN KEY (flagged_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_announcements_body_present CHECK (length(btrim(body)) > 0)
);

-- The read path is "this cohort's posts, newest first", always org-scoped.
CREATE INDEX idx_announcements_cohort ON announcements (org_id, cohort_id, created_at DESC);
-- Serves the users(id) FK-cascade scans on erasure.
CREATE INDEX idx_announcements_author ON announcements (author_id);
CREATE INDEX idx_announcements_flagged_by ON announcements (flagged_by);
