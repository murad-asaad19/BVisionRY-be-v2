-- =============================================================================
-- V167__cohort_lifecycle_quota.sql — cohort lifecycle + launch-quota ledger
-- (redesign spec §2.3 + §8, phase F1)
-- =============================================================================
-- The cohort lifecycle becomes DRAFT → LAUNCHED → COMPLETED / ARCHIVED.
-- Drafts are free; LAUNCHING consumes plan quota (calendar periods, enforced at
-- the ROOT org). The old creation-time rolling-window ceiling is REPLACED by
-- this model (spec §8: "drafts are free; launching consumes quota").
--
-- Nothing on this branch is deployed, so the status values are rebuilt in
-- place (run-state: rebuilding allowed) rather than expand-only:
--   ACTIVE  → LAUNCHED   (was: learners working through it)
--   FINISHED→ COMPLETED  (was: read-only)
-- New cohorts default to DRAFT (invisible to members until launched).
-- =============================================================================

-- ---------------------------------------------------------------- lifecycle
ALTER TABLE cohorts ADD COLUMN launched_at  timestamptz NULL;
ALTER TABLE cohorts ADD COLUMN completed_at timestamptz NULL;
ALTER TABLE cohorts ADD COLUMN archived_at  timestamptz NULL;

-- §7b backfill: a migrated running/finished cohort was de-facto launched when
-- it was created; a finished one completed at its last update. Conservative
-- lower bounds, but never NULL for a state that implies the event happened.
UPDATE cohorts SET launched_at  = created_at WHERE status IN ('ACTIVE', 'FINISHED');
UPDATE cohorts SET completed_at = updated_at WHERE status = 'FINISHED';

-- ------------------------------------------------- launch ledger (append-only)
-- One row per launch, forever. Deleting a cohort NEVER refunds quota, so
-- cohort_id carries NO foreign key at all: a ledger row must survive the
-- cohort's deletion (an FK — even ON DELETE NO ACTION — would block the
-- delete instead). org_id is the BILLING ROOT org (sub-org cohorts consume
-- the root family's quota); it cascades with org hard-delete because a
-- deleted customer has no quota left to audit. launched_by survives user
-- deletion as NULL (the launch still happened).
CREATE TABLE cohort_launch_ledger (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id      uuid        NOT NULL,
    cohort_id   uuid        NOT NULL,
    launched_at timestamptz NOT NULL DEFAULT now(),
    launched_by uuid        NULL,

    CONSTRAINT pk_cohort_launch_ledger PRIMARY KEY (id),
    CONSTRAINT fk_launch_ledger_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_launch_ledger_user FOREIGN KEY (launched_by)
        REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_launch_ledger_org_launched ON cohort_launch_ledger (org_id, launched_at);

-- Backfill BEFORE the status rewrite: every migrated ACTIVE/FINISHED cohort
-- was launched under the old model, so the meter keeps counting it — without
-- this, every org would get a silent free launch on deploy. Root-org
-- resolution mirrors the billing-family rule (hierarchy is one level deep).
INSERT INTO cohort_launch_ledger (org_id, cohort_id, launched_at)
SELECT COALESCE(o.parent_organization_id, o.id), c.id, c.created_at
FROM cohorts c
JOIN organizations o ON o.id = c.org_id
WHERE c.status IN ('ACTIVE', 'FINISHED');

-- --------------------------------------------------------- status rewrite
ALTER TABLE cohorts DROP CONSTRAINT ck_cohorts_status;

UPDATE cohorts SET status = 'LAUNCHED'  WHERE status = 'ACTIVE';
UPDATE cohorts SET status = 'COMPLETED' WHERE status = 'FINISHED';

ALTER TABLE cohorts ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE cohorts ADD CONSTRAINT ck_cohorts_status
    CHECK (status IN ('DRAFT', 'LAUNCHED', 'COMPLETED', 'ARCHIVED'));

-- ----------------------------------------------------------- launch grants
-- A SUPER_ADMIN override: each row grants +1 launch in the calendar period it
-- was created in (lifetime for trial orgs). org_id is the billing root.
CREATE TABLE cohort_launch_grants (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL,
    granted_by uuid        NULL,
    note       varchar(500) NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_cohort_launch_grants PRIMARY KEY (id),
    CONSTRAINT fk_launch_grants_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_launch_grants_user FOREIGN KEY (granted_by)
        REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_launch_grants_org_created ON cohort_launch_grants (org_id, created_at);

-- ------------------------------------------------------- module pillar chip
-- The module's pillar/area label shown as a chip on the cohort board and the
-- member journey (spec §2.3; D2 seam). Free text this phase; a structured
-- pillar reference is a later, deliberate change.
ALTER TABLE program_modules ADD COLUMN pillar_label varchar(120) NULL;
