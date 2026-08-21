-- =============================================================================
-- V147__coach_role_and_assignments.sql — COACH role + coach assignments
-- =============================================================================
-- Roadmap §7 item 4 / §8 (coach console). Expand-only:
--   1. Widen the role CHECKs to admit 'COACH'. 'MANAGER' stays in the allowed
--      set on purpose — the contraction that drops it from the CHECK is a
--      later, operator-era migration. With the Java enum constant removed in
--      the same release, nothing can WRITE the value any more; keeping it in
--      the CHECK just makes this migration a pure expansion.
--   2. Migrate MANAGER holders to MEMBER (closed decision §14.2: manager DELETE,
--      holders become MEMBER). ALL MANAGER invitation rows become MEMBER too,
--      whatever their status: Invitation.role hydrates through the UserRole
--      enum, so any surviving MANAGER row — accepted, revoked or expired —
--      would throw on read and 500 the whole invitations surface; and a
--      pending one accepted later would mint a role the application can no
--      longer represent. MEMBER matches what actually happened to the holders.
--   3. coach_assignments — who a coach may see. Two grains in one table
--      (policy: coach_assignment_grain COHORT_AND_DIRECT): a row is EITHER a
--      whole-cohort grant (cohort_id set) OR a direct founder grant (member_id
--      set), never both. A coach's visibility is the UNION of their rows.
--      org_id denormalises the tenant so every visibility predicate can carry
--      the org equality without a join, and so cross-org rows are structurally
--      absurd rather than merely unqueried.
--
-- Erasure/FK design (PersonalDataCoverageTest):
--   coach_id / member_id CASCADE — a deleted user's grants (given or received)
--   die with the row; assigned_by SET NULL — attribution on the org's record
--   survives the admin's own erasure.
-- =============================================================================

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('SUPER_ADMIN', 'ORG_ADMIN', 'INSTRUCTOR', 'COACH', 'MANAGER', 'MEMBER'));

ALTER TABLE invitations DROP CONSTRAINT IF EXISTS invitations_role_check;
ALTER TABLE invitations ADD CONSTRAINT invitations_role_check
    CHECK (role IN ('ORG_ADMIN', 'INSTRUCTOR', 'COACH', 'MANAGER', 'MEMBER'));

UPDATE users SET role = 'MEMBER' WHERE role = 'MANAGER';
UPDATE invitations SET role = 'MEMBER' WHERE role = 'MANAGER';

CREATE TABLE coach_assignments (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id      uuid        NOT NULL,
    coach_id    uuid        NOT NULL,
    cohort_id   uuid,
    member_id   uuid,
    assigned_by uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_coach_assignments PRIMARY KEY (id),
    CONSTRAINT fk_coach_assignments_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_coach_assignments_coach FOREIGN KEY (coach_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_coach_assignments_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_coach_assignments_member FOREIGN KEY (member_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_coach_assignments_assigned_by FOREIGN KEY (assigned_by)
        REFERENCES users (id) ON DELETE SET NULL,
    -- exactly one grain per row
    CONSTRAINT ck_coach_assignments_grain CHECK (num_nonnulls(cohort_id, member_id) = 1)
);

-- One grant per (coach, cohort) and per (coach, founder).
CREATE UNIQUE INDEX uq_coach_assignments_cohort
    ON coach_assignments (coach_id, cohort_id) WHERE cohort_id IS NOT NULL;
CREATE UNIQUE INDEX uq_coach_assignments_member
    ON coach_assignments (coach_id, member_id) WHERE member_id IS NOT NULL;

CREATE INDEX idx_coach_assignments_org ON coach_assignments (org_id, created_at);
-- Coach-leading so the users(id) FK-cascade scan is served too; both columns
-- are equality predicates in the visibility SQL, so the order is free there.
CREATE INDEX idx_coach_assignments_coach ON coach_assignments (coach_id, org_id);
CREATE INDEX idx_coach_assignments_cohort ON coach_assignments (cohort_id);
CREATE INDEX idx_coach_assignments_member ON coach_assignments (member_id);
CREATE INDEX idx_coach_assignments_assigned_by ON coach_assignments (assigned_by);
