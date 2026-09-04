-- =============================================================================
-- V214__cohort_pillar_notes.sql — the coach's sentence under a pillar
-- The cohort Growth tab sorts pillars into working well / needs focus / not
-- yet built from computed numbers; the one thing it cannot compute is WHY —
-- the mechanism a coach names ("the habit log is producing real evidence").
-- One optional note per (org, cohort, distance pillar): a platform cohort
-- spans orgs and each org reads its own slice, so the org is part of the key.
--
-- Both cohort and pillar FKs cascade, like session_pillars (V207): the note is
-- a remark about two things that exist and must not outlive either.
-- =============================================================================

CREATE TABLE cohort_pillar_notes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    cohort_id   uuid NOT NULL REFERENCES cohorts (id) ON DELETE CASCADE,
    pillar_id   uuid NOT NULL REFERENCES pillars (id) ON DELETE CASCADE,
    note        text NOT NULL,
    updated_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_cohort_pillar_notes UNIQUE (org_id, cohort_id, pillar_id)
);
