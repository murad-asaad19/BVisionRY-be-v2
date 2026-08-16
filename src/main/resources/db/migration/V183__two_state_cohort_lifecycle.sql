-- =============================================================================
-- V183__two_state_cohort_lifecycle.sql
-- Collapse the cohort lifecycle to DRAFT <-> LAUNCHED (operator decision
-- 2026-08-15). A cohort is shared by several organizations, so a single
-- "completed" flag never described anyone's reality, and ARCHIVED was just
-- shelving that DRAFT now covers. The lifecycle becomes a two-way toggle:
-- launch makes it live, unlaunch pulls it back to draft (quota stands either
-- way — the ledger is append-only, spec §8).
--
--   COMPLETED -> LAUNCHED  (still ongoing; members keep access)
--   ARCHIVED  -> DRAFT     (hidden from members until relaunched)
--
-- Completion returns as a PER-ORGANISATION concept (operator decision
-- 2026-08-16): a completion marker on cohort_orgs, putting that org's members
-- into read-only. Tracked in BVisionRY-be-v2 issue "Per-org cohort completion:
-- members of a completed org go read-only".
-- =============================================================================

UPDATE cohorts SET status = 'LAUNCHED' WHERE status = 'COMPLETED';
UPDATE cohorts SET status = 'DRAFT', launched_at = NULL WHERE status = 'ARCHIVED';

ALTER TABLE cohorts DROP CONSTRAINT ck_cohorts_status;
ALTER TABLE cohorts ADD CONSTRAINT ck_cohorts_status
    CHECK (status IN ('DRAFT', 'LAUNCHED'));

ALTER TABLE cohorts DROP COLUMN completed_at;
ALTER TABLE cohorts DROP COLUMN archived_at;
