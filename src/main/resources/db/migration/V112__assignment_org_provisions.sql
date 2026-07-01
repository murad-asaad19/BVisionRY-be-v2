-- Org-level assignment provisions: a pipeline assigned to an organization but not
-- yet distributed to individual members (user_id IS NULL). Super admins create
-- these; org admins assign members against them.

ALTER TABLE assignments
    ALTER COLUMN user_id DROP NOT NULL;

DROP INDEX IF EXISTS uq_assignments_org_pipeline_user;

CREATE UNIQUE INDEX uq_assignments_org_pipeline_user
    ON assignments (organization_id, pipeline_id, user_id)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_assignments_org_pipeline_provision
    ON assignments (organization_id, pipeline_id)
    WHERE user_id IS NULL;

-- Backfill org-level provisions for pipelines already assigned to members,
-- so org admins retain access after this migration without manual re-provisioning.
INSERT INTO assignments (
    pipeline_id, organization_id, assigned_by, user_id, deadline, max_check_ins, created_at, updated_at
)
SELECT DISTINCT ON (a.organization_id, a.pipeline_id)
    a.pipeline_id,
    a.organization_id,
    a.assigned_by,
    NULL,
    NULL,
    1,
    NOW(),
    NOW()
FROM assignments a
WHERE a.user_id IS NOT NULL
ORDER BY a.organization_id, a.pipeline_id, a.created_at ASC;
