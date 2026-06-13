-- Move assignments from per-organization-batch to per-member.
-- Each assignment row now represents exactly one (pipeline, organization, user) tuple.

-- 0. Drop the old "one assignment per (pipeline, organization)" constraint from V20.
--    It's replaced by a stricter "one per (pipeline, organization, user)" index at the
--    end of this migration. We must drop it BEFORE backfilling, otherwise fanning out
--    into multiple per-member rows for the same (pipeline, org) fails with 23505.
ALTER TABLE assignments
    DROP CONSTRAINT IF EXISTS uq_assignment_pipeline_org;

-- 1. Add the user_id column (nullable while we backfill).
ALTER TABLE assignments
    ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE CASCADE;

-- 2. Backfill: fan out every old assignment into one new assignment per submission.
--    Each submission already has a user_id; we create a dedicated assignment for it and
--    re-parent the submission to the new row. Old aggregate rows end up orphaned and
--    are deleted in step 3.
DO $$
DECLARE
    s RECORD;
    new_assignment_id UUID;
BEGIN
    FOR s IN
        SELECT sub.id AS submission_id,
               sub.user_id,
               a.id AS old_assignment_id,
               a.pipeline_id,
               a.organization_id,
               a.assigned_by,
               a.assigned_to,
               a.deadline,
               a.created_at
        FROM submissions sub
        JOIN assignments a ON a.id = sub.assignment_id
    LOOP
        -- Skip if an assignment already exists for this (org, pipeline, user).
        -- Can happen if the data was partially migrated in a prior run.
        SELECT id INTO new_assignment_id
        FROM assignments
        WHERE organization_id = s.organization_id
          AND pipeline_id = s.pipeline_id
          AND user_id = s.user_id
        LIMIT 1;

        IF new_assignment_id IS NULL THEN
            INSERT INTO assignments (
                id, pipeline_id, organization_id, assigned_by, assigned_to,
                deadline, user_id, created_at, updated_at
            ) VALUES (
                gen_random_uuid(),
                s.pipeline_id,
                s.organization_id,
                s.assigned_by,
                s.assigned_to,
                s.deadline,
                s.user_id,
                s.created_at,
                NOW()
            )
            RETURNING id INTO new_assignment_id;
        END IF;

        UPDATE submissions
            SET assignment_id = new_assignment_id
            WHERE id = s.submission_id;
    END LOOP;
END $$;

-- 3. Delete aggregate rows that nothing points at any more.
DELETE FROM assignments a
WHERE a.user_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM submissions s WHERE s.assignment_id = a.id);

-- 4. Enforce the new invariants.
ALTER TABLE assignments
    ALTER COLUMN user_id SET NOT NULL;

-- assigned_to is meaningless now (each assignment has exactly one user).
ALTER TABLE assignments
    DROP COLUMN assigned_to;

-- Prevent re-assigning the same pipeline to the same member in the same org.
CREATE UNIQUE INDEX IF NOT EXISTS uq_assignments_org_pipeline_user
    ON assignments (organization_id, pipeline_id, user_id);

CREATE INDEX IF NOT EXISTS idx_assignments_user
    ON assignments (user_id);
