CREATE TABLE join_links (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token      UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    is_active  BOOLEAN NOT NULL DEFAULT true,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Only one active link per org at a time
CREATE UNIQUE INDEX uq_join_links_active_org ON join_links (organization_id) WHERE is_active = true;
