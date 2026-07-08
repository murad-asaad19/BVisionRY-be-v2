-- =============================================================================
-- V122__cohorts.sql — multiple cohorts per organization
-- =============================================================================
-- The program flow was implicitly ONE program per org (program_modules /
-- program_settings scoped by org_id). A "cohort" now owns a program: an org can
-- run many cohorts, each its own modules + settings, each with an enrolled set
-- of learners and an ACTIVE / FINISHED lifecycle (FINISHED = read-only for
-- learners, who keep seeing their results/answers/leaderboard).
--
-- Migration path: every org with existing program data gets one default cohort
-- ("Cohort 1") that adopts its modules + settings and enrols all current
-- members, so today's single program is preserved unchanged as cohort #1.
--
-- Deletes cascade org → cohorts → modules/settings/enrolment, so org hard-delete
-- (OrganizationService.hardDelete) keeps wiping all program data with no change.
-- =============================================================================

-- ----------------------------------------------------------------------------
-- cohorts — a program instance within an org (drip modules + settings + roster)
-- ----------------------------------------------------------------------------
CREATE TABLE cohorts (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id      uuid         NOT NULL,
    name        varchar(200) NOT NULL,
    position    integer      NOT NULL DEFAULT 0,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_cohorts PRIMARY KEY (id),
    CONSTRAINT fk_cohorts_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_cohorts_status CHECK (status IN ('ACTIVE', 'FINISHED'))
);

CREATE INDEX idx_cohorts_org ON cohorts (org_id, position);

-- ----------------------------------------------------------------------------
-- cohort_members — which learners are enrolled in a cohort (a learner may be in
-- several cohorts and switch between them on the journey).
-- ----------------------------------------------------------------------------
CREATE TABLE cohort_members (
    cohort_id  uuid NOT NULL,
    user_id    uuid NOT NULL,

    CONSTRAINT pk_cohort_members PRIMARY KEY (cohort_id, user_id),
    CONSTRAINT fk_cohort_members_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cohort_members_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_cohort_members_user ON cohort_members (user_id);

-- ----------------------------------------------------------------------------
-- Backfill: one default cohort per org that already has program data.
-- ----------------------------------------------------------------------------
INSERT INTO cohorts (org_id, name, position, status)
SELECT o.id, 'Cohort 1', 0, 'ACTIVE'
FROM organizations o
WHERE EXISTS (SELECT 1 FROM program_modules pm WHERE pm.org_id = o.id)
   OR EXISTS (SELECT 1 FROM program_settings ps WHERE ps.org_id = o.id);

-- Enrol every current active org member into that default cohort.
INSERT INTO cohort_members (cohort_id, user_id)
SELECT c.id, u.id
FROM cohorts c
JOIN users u ON u.organization_id = c.org_id
WHERE u.role = 'MEMBER' AND u.status = 'ACTIVE';

-- ----------------------------------------------------------------------------
-- program_modules → belong to a cohort (keep org_id: audience/teams are org-scoped).
-- ----------------------------------------------------------------------------
ALTER TABLE program_modules ADD COLUMN cohort_id uuid;

UPDATE program_modules pm
SET cohort_id = c.id
FROM cohorts c
WHERE c.org_id = pm.org_id;

ALTER TABLE program_modules ALTER COLUMN cohort_id SET NOT NULL;
ALTER TABLE program_modules ADD CONSTRAINT fk_program_modules_cohort
    FOREIGN KEY (cohort_id) REFERENCES cohorts (id) ON DELETE CASCADE;

CREATE INDEX idx_program_modules_cohort ON program_modules (cohort_id, position);

-- ----------------------------------------------------------------------------
-- program_settings → keyed per cohort (was PK org_id, 1:1 with org).
-- ----------------------------------------------------------------------------
ALTER TABLE program_settings ADD COLUMN cohort_id uuid;

UPDATE program_settings ps
SET cohort_id = c.id
FROM cohorts c
WHERE c.org_id = ps.org_id;

ALTER TABLE program_settings DROP CONSTRAINT pk_program_settings;
ALTER TABLE program_settings DROP CONSTRAINT fk_program_settings_org;
ALTER TABLE program_settings DROP COLUMN org_id;

ALTER TABLE program_settings ALTER COLUMN cohort_id SET NOT NULL;
ALTER TABLE program_settings ADD CONSTRAINT pk_program_settings PRIMARY KEY (cohort_id);
ALTER TABLE program_settings ADD CONSTRAINT fk_program_settings_cohort
    FOREIGN KEY (cohort_id) REFERENCES cohorts (id) ON DELETE CASCADE;
