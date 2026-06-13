-- An auto-assignment rule says: "any current or future ACTIVE member in this
-- organization (optionally restricted to user_type) should have this pipeline
-- assigned automatically." The accompanying member-joined event listener
-- materialises one assignments row per matching new joiner.
--
-- Rules are scoped per (organization, pipeline, user_type). user_type IS NULL
-- means "everyone in the org". A typed rule and an org-wide rule are both
-- allowed for the same (org, pipeline) — they are independent — so the
-- uniqueness invariant is split across two partial indexes.

CREATE TABLE pipeline_auto_assignments (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    pipeline_id     UUID NOT NULL REFERENCES pipelines(id)     ON DELETE CASCADE,
    user_type       VARCHAR(64),
    deadline        TIMESTAMP WITH TIME ZONE,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_pipeline_auto_assignments_typed
    ON pipeline_auto_assignments (organization_id, pipeline_id, user_type)
    WHERE user_type IS NOT NULL;

CREATE UNIQUE INDEX uq_pipeline_auto_assignments_orgwide
    ON pipeline_auto_assignments (organization_id, pipeline_id)
    WHERE user_type IS NULL;

CREATE INDEX idx_pipeline_auto_assignments_org
    ON pipeline_auto_assignments (organization_id);

CREATE INDEX idx_pipeline_auto_assignments_pipeline
    ON pipeline_auto_assignments (pipeline_id);
