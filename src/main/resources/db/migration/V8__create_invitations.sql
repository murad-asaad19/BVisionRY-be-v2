CREATE TABLE invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    token UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ORG_ADMIN', 'MEMBER')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    invited_by UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invitations_token ON invitations (token);
CREATE INDEX idx_invitations_org ON invitations (organization_id);
CREATE INDEX idx_invitations_email_org ON invitations (email, organization_id);
