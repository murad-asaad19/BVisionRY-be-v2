-- Brings the live insight_report_member_ids schema in line with the corrected
-- V57 (which was edited after some environments had already applied the
-- original version). Idempotent so it's safe whether the env has the original
-- V57 only or already-corrected V57.
--
-- 1. Drop the redundant single-column index. The composite primary key
--    (insight_report_id, member_id) already serves leading-column lookups via
--    its btree, so a separate single-column index is just bloat.
-- 2. Add ON DELETE CASCADE FK on member_id → users(id). Organization deletion
--    hard-deletes its users (V34 cascade); without this FK, dangling rows in
--    insight_report_member_ids would surface as "unknown member" in PDF/Excel
--    exports.

DROP INDEX IF EXISTS idx_insight_report_member_ids_report;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'insight_report_member_ids'
          AND constraint_name = 'fk_insight_report_member_ids_user'
    ) THEN
        ALTER TABLE insight_report_member_ids
            ADD CONSTRAINT fk_insight_report_member_ids_user
            FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
END
$$;
