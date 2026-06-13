-- Remove assessment cycle feature columns

ALTER TABLE pipelines DROP COLUMN IF EXISTS cycle_type;
ALTER TABLE pipelines DROP COLUMN IF EXISTS cadence;
ALTER TABLE assignments DROP COLUMN IF EXISTS cycle_number;
ALTER TABLE insight_reports DROP COLUMN IF EXISTS cycle_number;
