-- Member-initiated upgrade requests. A row exists for every "Request upgrade"
-- click; the same user clicking again within the cooldown window is rejected
-- by the service via the (requested_by, created_at DESC) index lookup.
--
-- No status column: there is no admin approve/decline workflow. The 24h
-- cooldown is computed from created_at; tier flips are performed manually
-- by SUPER_ADMIN out-of-band, after which the gate UI no longer renders
-- (because the org is now PREMIUM).

CREATE TABLE upgrade_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    requested_by    UUID        NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    feature_context VARCHAR(32),
    note            VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Required by BaseEntity even though rows are append-only.
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Cooldown lookup: latest request per user. DESC so the most recent row is
-- the index head — the eligibility check reads exactly one row.
CREATE INDEX ix_upgrade_requests_member_created
    ON upgrade_requests (requested_by, created_at DESC);

-- Org rollup (e.g. "5 members from Test Org asked this week") — cheap to
-- have for the activity feed and any future demand reporting.
CREATE INDEX ix_upgrade_requests_org_created
    ON upgrade_requests (organization_id, created_at DESC);
