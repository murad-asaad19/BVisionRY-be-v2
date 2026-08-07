-- =============================================================================
-- V160__org_storage_quota.sql — per-org object-storage quota override
-- (roadmap §11: uploads were capped at 10MB/file and UNBOUNDED IN COUNT, a
-- cost/availability vector reachable by any ORG_ADMIN via repeated white-label
-- logo uploads — each presign mints a fresh object key, V154's IDOR guard
-- constrains WHERE it can land, never how many). OPERATOR RULING: 2 GiB
-- default ceiling, configurable, with this column as the per-org override.
-- =============================================================================
-- Expand-only: one NULLABLE column, no table, no backfill — same reasoning
-- V149 (inactivity_nudge_days) and V154 (brand_color/brand_logo_marker)
-- already recorded: no per-org key/value store exists in this schema, so a
-- typed column with a CHECK is the established knob.
--
-- NULL means "no override — use the platform default"
-- (bvisionry.minio.org-default-quota-bytes, currently 2 GiB). A platform-wide
-- default change therefore does not require touching every org row.
--
-- Usage is NOT tracked in a counter column here — that would drift the moment
-- an object is deleted or added outside a code path that remembers to
-- increment/decrement it. MediaService computes usage live by summing the
-- real size of every object under org/<id>/ in MinIO (the object store is
-- already the source of truth for "how many bytes does this org have
-- stored"), so nothing new is READ from this table except the override.
-- =============================================================================

ALTER TABLE organizations
    ADD COLUMN storage_quota_bytes BIGINT;

ALTER TABLE organizations
    ADD CONSTRAINT ck_organizations_storage_quota_bytes_positive
        CHECK (storage_quota_bytes IS NULL OR storage_quota_bytes > 0);
