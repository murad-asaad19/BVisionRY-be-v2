-- =============================================================================
-- V73: Stamp audit_logs with organization_id at write time so the org Activity
-- feed scopes by an immutable column instead of joining through the actor's
-- *current* organization (which silently dropped rows when a user was moved
-- or removed).
-- =============================================================================

ALTER TABLE audit_logs
    ADD COLUMN organization_id UUID;

ALTER TABLE audit_logs
    ADD CONSTRAINT fk_audit_logs__organization_id
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE SET NULL;

-- Backfill, in priority order. Each step only fills rows that earlier steps
-- left null, so the most reliable signal wins.

-- audit_logs.entity_id and details_json->>'organizationId' have no FK, so
-- they can reference organizations that were since deleted. Each backfill
-- step gates the UPDATE on EXISTS against organizations so the new FK
-- constraint isn't violated by a dangling reference.

-- 1. Rows whose entity *is* the organization carry the org id directly.
UPDATE audit_logs a
   SET organization_id = a.entity_id
 WHERE a.organization_id IS NULL
   AND a.entity_type = 'Organization'
   AND a.entity_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM organizations o WHERE o.id = a.entity_id);

-- 2. Many call sites already stamped organizationId into details_json
--    (CLEAR_RESPONSES, MEMBER_REMOVED, JOIN_LINK_USED, LINK_GENERATED, etc.).
UPDATE audit_logs a
   SET organization_id = (a.details_json->>'organizationId')::uuid
 WHERE a.organization_id IS NULL
   AND a.details_json ? 'organizationId'
   AND (a.details_json->>'organizationId') ~ '^[0-9a-fA-F-]{36}$'
   AND EXISTS (
       SELECT 1 FROM organizations o
        WHERE o.id = (a.details_json->>'organizationId')::uuid
   );

-- 3. Best-effort fallback: actor's current organization. This matches the
--    legacy activity-feed behaviour exactly, so no row that *was* visible
--    becomes invisible after this migration. Going forward, new rows are
--    stamped explicitly at write time.
UPDATE audit_logs a
   SET organization_id = u.organization_id
  FROM users u
 WHERE a.organization_id IS NULL
   AND a.actor_id = u.id
   AND u.organization_id IS NOT NULL;

-- The activity feed orders by occurred_at DESC and filters by organization_id,
-- so the leading column matches the equality predicate and the second column
-- avoids a sort. The USER_LOGIN exclusion stays a heap filter — the matched
-- set is already org-bounded and small.
CREATE INDEX ix_audit_logs_organization_occurred
    ON audit_logs (organization_id, occurred_at DESC)
    WHERE organization_id IS NOT NULL;
