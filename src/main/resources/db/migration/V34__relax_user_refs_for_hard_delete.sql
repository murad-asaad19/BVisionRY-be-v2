-- Hard-deleting an organization cascades to deleting all its users. Leftover
-- references to those users (from pipelines they created, other users they
-- invited, audit rows they emitted) would fail without these adjustments.

-- 1. pipelines.created_by must survive the creator's deletion.
ALTER TABLE pipelines
    ALTER COLUMN created_by DROP NOT NULL;

ALTER TABLE pipelines
    DROP CONSTRAINT IF EXISTS pipelines_created_by_fkey;

ALTER TABLE pipelines
    ADD CONSTRAINT pipelines_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- 2. users.invited_by is already nullable, but needs SET NULL on referenced user delete.
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_invited_by_fkey;

ALTER TABLE users
    ADD CONSTRAINT users_invited_by_fkey
    FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE SET NULL;

-- 3. audit_logs.actor_id is already nullable; likewise add SET NULL so deleting a
--    user doesn't wipe their audit trail — the row survives with actor_id = NULL.
ALTER TABLE audit_logs
    DROP CONSTRAINT IF EXISTS audit_logs_actor_id_fkey;

ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_actor_id_fkey
    FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL;
