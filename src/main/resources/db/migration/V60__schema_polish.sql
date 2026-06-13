-- =============================================================================
-- V60: Schema polish — audit_logs rename, supporting indexes, and a partial
-- unique guard on pending invitations.
--
-- Going-forward constraint/index naming convention (followed from this
-- migration onward; existing names are not retroactively renamed because the
-- churn outweighs the value):
--   pk_<table>                       — primary key
--   fk_<table>__<col>                — foreign key
--   ck_<table>_<rule>                — CHECK constraint
--   ix_<table>_<cols>                — non-unique index
--   ux_<table>_<cols>                — UNIQUE index (use partial WHERE clause
--                                      where the uniqueness is conditional)
-- Future migrations should use these prefixes; mixing snake_case columns into
-- the suffix is fine (e.g. ix_audit_logs_entity_action_time).
-- =============================================================================

-- 1. Rename audit_logs.timestamp to occurred_at.
--    "timestamp" is a SQL/Postgres keyword (data type) and quoting it in
--    every JPQL/SQL site is a maintenance hazard. occurred_at is unambiguous.
ALTER TABLE audit_logs RENAME COLUMN "timestamp" TO occurred_at;

-- 2. Partial unique index: at most one PENDING invitation per (email, org).
--    Resolved/declined invitations remain duplicable so an invitee can be
--    re-invited after a previous attempt expired or was revoked.
CREATE UNIQUE INDEX ux_invitations_pending_email_org
    ON invitations (email, organization_id)
    WHERE status = 'PENDING';

-- 3. Composite index for the most expensive audit lookup pattern:
--    findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc
--    Used by AttentionRuleService (per-org suspended/trial-expired probes)
--    and TrialService (per-org duplicate-notification check).
CREATE INDEX idx_audit_logs_entity_action_time
    ON audit_logs (entity_type, entity_id, action_type, occurred_at DESC);
