-- =============================================================================
-- V157__enrolment_override.sql — "an admin can override" (roadmap §7 item 10).
-- =============================================================================
-- The auto-enrolment engine shipped in V150/V151; its one remaining acceptance
-- criterion did not. Once AutoEnrolmentService put a founder on a course,
-- nothing could take them off it: EnrollmentController exposes only self-enrol
-- and mark-complete, and FounderEnrolmentWriteRepository is INSERT-only by
-- design. This is the missing half.
--
-- Expand-only: one new table. Nothing dropped, renamed or narrowed, and in
-- particular `enrollment` is NOT altered — see "WHY NO NEW COLUMN" below.
--
-- WHY A TABLE AND NOT A LEDGER ROW. auto_enrolments is keyed
-- (user_id, course_id, submission_id) — agent-policy's
-- auto_enrolment_idempotency_key. That grain is exactly wrong for an override:
-- a NEW evaluation carries a NEW submission_id, so a decision recorded against
-- one submission says nothing about the next one, and the founder would be
-- re-enrolled in the course the admin just removed. An override that a later
-- assessment silently undoes is not an override. So this table drops
-- submission_id on purpose: it is keyed (user_id, course_id) and outlives every
-- evaluation, which is the entire point of it.
--
-- WHY NOT A FOURTH auto_enrolments.outcome. It would need DROP CONSTRAINT
-- ck_auto_enrolments_outcome then re-ADD, and V151 states that widening in
-- terms this migration honours: "That is a CONTRACTION, which this run's policy
-- reserves to a human (never_auto_decide) — an agent widening this list must
-- park and ask, not write the migration." So the engine writes NOTHING for a
-- course an admin has overridden, the same silence an off-axis pillar produces,
-- and THIS row is the record of why. It carries more than an enum value could
-- anyway: who, when, and in their own words.
--
-- WHY NO NEW COLUMN ON `enrollment`. The removal itself needs no schema change:
-- enrollment.status already admits 'CANCELLED' (V79's ck_enrollment_status) and
-- nothing has ever written it. An admin removal sets that value and leaves
-- progress_pct, completed_at and every child row alone — see MemberCourseRepository
-- for why detaching beats deleting.
--
-- NO org_id, for auto_enrolments' reason (V151): an enrolment is (user, course)
-- and nothing else, because the catalog is a public cross-org surface.
-- Attribution is user_id; the tenancy check is the ORG_ADMIN's own gate on
-- /api/organizations/{orgId}/members/{memberId}, which proves the founder is
-- theirs before this row is ever written.
--
-- FKs: user_id and course_id CASCADE so an override can never outlive what it
-- suppresses (V150/V151's rule). removed_by is SET NULL, matching V135's
-- relaxation for attribution columns — deleting the admin who made the call must
-- not delete the call.
-- =============================================================================

CREATE TABLE enrolment_overrides (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users  (id) ON DELETE CASCADE,
    course_id  UUID NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    -- The admin who removed them. Nullable only because of the SET NULL above.
    removed_by UUID          REFERENCES users  (id) ON DELETE SET NULL,
    -- Optional, in the admin's own words. Every other "why" in this feature is
    -- a machine's (a pillar, a band, an outcome enum); this is the one decision
    -- a person made, and an enum could not hold the reason for it.
    reason     TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- The idempotency key AND the engine's lookup key. One override per
    -- (founder, course), forever — re-removing is a no-op rather than a second
    -- row, and the auto-enrolment engine's read is an index-only hit on it.
    CONSTRAINT uq_enrolment_overrides UNIQUE (user_id, course_id)
);
