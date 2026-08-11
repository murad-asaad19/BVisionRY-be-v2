-- =============================================================================
-- V171__platform_cohorts.sql — cohorts become platform-level artifacts
-- (redesign spec §13, decided 2026-08-11)
-- =============================================================================
-- A cohort is no longer owned by one organization. Like a pipeline it is
-- authored at the platform level and ASSIGNED to one or more orgs; unlike a
-- pipeline it stays a run (lifecycle, drip, roster, submissions), so the
-- assignment row carries the enrollment rule, not just visibility:
--
--   • cohort_orgs (cohort_id, org_id, auto_enroll) — which orgs participate.
--     auto_enroll = future members joining that org are enrolled automatically
--     (the "auto assignation" mode, mirroring pipeline auto-assignment rules).
--     Backfilled from cohorts.org_id so every existing cohort keeps exactly
--     its current org and roster.
--   • Quota: each assigned org pays for its participation in a launched
--     cohort from its own billing-root plan (service-level; the append-only
--     cohort_launch_ledger schema already carries org_id per row).
--
-- Teams are DELETED OUTRIGHT (operator decision §13.5): audience modes reduce
-- to ALL / MEMBERS. Existing TEAMS audiences are resolved to their current
-- member lists first, so no module silently changes reach. Workshop teams are
-- a separate model (workshop_teams) and are untouched.
--
-- Ownership consequence: cohorts no longer cascade-delete with an org — a
-- platform artifact survives any single org's deletion (its cohort_orgs rows
-- and that org's members' cohort_members rows cascade away instead).
-- =============================================================================

-- ------------------------------------------------------------- cohort_orgs
CREATE TABLE cohort_orgs (
    cohort_id   uuid        NOT NULL,
    org_id      uuid        NOT NULL,
    auto_enroll boolean     NOT NULL DEFAULT false,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    -- SET NULL like every other admin attribution: the assignment outlives
    -- the assigning admin's account.
    assigned_by uuid        NULL,

    CONSTRAINT pk_cohort_orgs PRIMARY KEY (cohort_id, org_id),
    CONSTRAINT fk_cohort_orgs_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cohort_orgs_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_cohort_orgs_assigned_by FOREIGN KEY (assigned_by)
        REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_cohort_orgs_org ON cohort_orgs (org_id);

INSERT INTO cohort_orgs (cohort_id, org_id, assigned_at)
SELECT id, org_id, created_at FROM cohorts;

-- --------------------------------------------- resolve TEAMS audiences, then
-- drop the whole team model (teams / team_members / program_module_teams).
INSERT INTO program_module_members (module_id, user_id)
SELECT pmt.module_id, tm.user_id
FROM program_module_teams pmt
JOIN team_members tm ON tm.team_id = pmt.team_id
ON CONFLICT (module_id, user_id) DO NOTHING;

UPDATE program_modules SET assign_mode = 'MEMBERS' WHERE assign_mode = 'TEAMS';

ALTER TABLE program_modules DROP CONSTRAINT ck_program_modules_assign_mode;
ALTER TABLE program_modules ADD CONSTRAINT ck_program_modules_assign_mode
    CHECK (assign_mode IN ('ALL', 'MEMBERS'));

DROP TABLE program_module_teams;
DROP TABLE team_members;
DROP TABLE teams;

-- ------------------------------------------------- drop per-org ownership
-- Positions were unique per org; renumber globally (stable: creation order)
-- so the platform-level list has a deterministic order before the per-org
-- index disappears with the column.
UPDATE cohorts SET position = t.rn - 1
FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn FROM cohorts) t
WHERE cohorts.id = t.id;

ALTER TABLE cohorts DROP COLUMN org_id;
ALTER TABLE program_modules DROP COLUMN org_id;
-- Sessions were double-scoped (org + cohort); the cohort alone is the scope now.
ALTER TABLE sessions DROP COLUMN org_id;

CREATE INDEX idx_cohorts_position ON cohorts (position);
