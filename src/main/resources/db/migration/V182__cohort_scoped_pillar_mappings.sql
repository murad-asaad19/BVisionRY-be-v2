-- =============================================================================
-- V182__cohort_scoped_pillar_mappings.sql — pillar mappings become COHORT config
-- =============================================================================
-- The pair mapping was global per (baseline, distance) pipeline pair (V161):
-- every cohort sharing the two instruments shared one mapping, edited on the
-- platform Scoring & Labels page. Operator ruling 2026-08-15: the mapping is
-- cohort configuration — each cohort owns its rows, edited on the cohort's
-- Settings tab. Existing pair mappings are copied to every cohort whose
-- designated pair matches, so no cohort loses a manual remap.

ALTER TABLE comparison_pillar_mappings
    ADD COLUMN cohort_id uuid;

-- The old pair-scoped unique indexes must go BEFORE the backfill: two cohorts
-- designating the same pair each get a copy of its rows, which the old
-- (pair, pillar) uniqueness would reject.
DROP INDEX uq_cpm_baseline_pillar;
DROP INDEX uq_cpm_distance_pillar;
DROP INDEX ix_cpm_pair;

-- One copy per cohort that designates this pair today.
INSERT INTO comparison_pillar_mappings
    (cohort_id, baseline_pipeline_id, distance_pipeline_id,
     baseline_pillar_id, distance_pillar_id, source, updated_by,
     created_at, updated_at)
SELECT ps.cohort_id, m.baseline_pipeline_id, m.distance_pipeline_id,
       m.baseline_pillar_id, m.distance_pillar_id, m.source, m.updated_by,
       m.created_at, m.updated_at
  FROM comparison_pillar_mappings m
  JOIN program_settings ps
    ON ps.baseline_pipeline_id = m.baseline_pipeline_id
   AND ps.distance_pipeline_id = m.distance_pipeline_id
 WHERE m.cohort_id IS NULL;

-- The cohort-less originals have no owner in the new model.
DELETE FROM comparison_pillar_mappings WHERE cohort_id IS NULL;

ALTER TABLE comparison_pillar_mappings
    ALTER COLUMN cohort_id SET NOT NULL;
ALTER TABLE comparison_pillar_mappings
    ADD CONSTRAINT fk_cpm_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE;

-- Re-key uniqueness per cohort: each pillar appears at most once per cohort's
-- pair, on its own side.
CREATE UNIQUE INDEX uq_cpm_baseline_pillar
    ON comparison_pillar_mappings
        (cohort_id, baseline_pipeline_id, distance_pipeline_id, baseline_pillar_id)
    WHERE baseline_pillar_id IS NOT NULL;
CREATE UNIQUE INDEX uq_cpm_distance_pillar
    ON comparison_pillar_mappings
        (cohort_id, baseline_pipeline_id, distance_pipeline_id, distance_pillar_id)
    WHERE distance_pillar_id IS NOT NULL;
CREATE INDEX ix_cpm_cohort_pair
    ON comparison_pillar_mappings (cohort_id, baseline_pipeline_id, distance_pipeline_id);
