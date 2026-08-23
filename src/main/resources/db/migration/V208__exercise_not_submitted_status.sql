-- =============================================================================
-- V208__exercise_not_submitted_status.sql — "the member never turned this in"
--
-- IN_PROGRESS already means "not submitted yet", but it is an OPEN state: the
-- member is still working. NOT_SUBMITTED is the CLOSED one — a super admin
-- recording that the work never arrived. The distinction exists for the AI
-- narrative: an in-progress sheet is draft evidence, a not-submitted one is an
-- absence, and the prompt must be able to say which.
--
-- Set only through the super-admin override (PATCH .../{id}/status); the
-- member ⇄ admin handshake never produces it. A member CAN still submit out of
-- it (operator decision 2026-08-22) — turning up late corrects the record
-- rather than needing an admin to unlock it.
--
-- No data migration: nothing is NOT_SUBMITTED until somebody sets it. Existing
-- IN_PROGRESS rows keep their meaning untouched.
-- =============================================================================

ALTER TABLE exercise_submissions
    DROP CONSTRAINT exercise_submissions_status_check;

ALTER TABLE exercise_submissions
    ADD CONSTRAINT exercise_submissions_status_check
        CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'CHANGES_REQUESTED',
                          'REVIEWED', 'NOT_SUBMITTED'));
