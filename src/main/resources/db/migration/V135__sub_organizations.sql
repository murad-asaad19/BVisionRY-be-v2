-- Sub-organizations: an organization may have children, ONE level deep
-- (enforced in the service layer — a sub-org can never be a parent).
-- Sub-orgs inherit the parent's subscription tier and follow the parent's
-- active/suspended state; members, invitations, and results stay per-org.
ALTER TABLE organizations ADD COLUMN parent_organization_id UUID REFERENCES organizations(id);
CREATE INDEX idx_organizations_parent ON organizations(parent_organization_id) WHERE parent_organization_id IS NOT NULL;
