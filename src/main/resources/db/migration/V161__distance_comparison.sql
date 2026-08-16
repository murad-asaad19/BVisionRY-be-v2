-- =============================================================================
-- V161__distance_comparison.sql — Distance Comparison (redesign spec §5, §7)
-- =============================================================================
-- Three pieces:
--   1. Pair designation: a cohort names its baseline + distance assessment
--      pipelines (org-admin cohort config, program_settings columns).
--   2. Pillar pair mapping: GLOBAL per (baseline, distance) pipeline pair —
--      auto-seeded by name match, overridable by SUPER_ADMIN only. Rows with
--      one NULL side model "newly measured" (no baseline pillar) and
--      "not re-measured" (no distance pillar) so dropped pillars are never
--      silently omitted.
--   3. The stored comparison object per founder × designated pair, computed
--      once when the distance submission is evaluated. It is a SNAPSHOT:
--      pillar ids are bare (no FK to pillars) and names/labels/bands are
--      stamped, so later pipeline edits or config changes never rewrite
--      history; only the explicit "Recompute cohort" action does.
-- =============================================================================

-- 1 ── pair designation on the cohort's program settings ----------------------

ALTER TABLE program_settings
    ADD COLUMN baseline_pipeline_id uuid,
    ADD COLUMN distance_pipeline_id uuid;

ALTER TABLE program_settings
    ADD CONSTRAINT fk_program_settings_baseline_pipeline
        FOREIGN KEY (baseline_pipeline_id) REFERENCES pipelines (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_program_settings_distance_pipeline
        FOREIGN KEY (distance_pipeline_id) REFERENCES pipelines (id) ON DELETE SET NULL;

-- 2 ── global pillar pair mapping ---------------------------------------------
-- updated_by is a bare uuid ON PURPOSE (no FK to users): it is an operator
-- attribution on platform config, the same stance as leaving it off the GDPR
-- erasure path would require — no FK means no erasure decision to carry.

CREATE TABLE comparison_pillar_mappings (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    baseline_pipeline_id uuid        NOT NULL,
    distance_pipeline_id uuid        NOT NULL,
    baseline_pillar_id   uuid,
    distance_pillar_id   uuid,
    source               text        NOT NULL,
    updated_by           uuid,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_comparison_pillar_mappings PRIMARY KEY (id),
    CONSTRAINT fk_cpm_baseline_pipeline FOREIGN KEY (baseline_pipeline_id)
        REFERENCES pipelines (id) ON DELETE CASCADE,
    CONSTRAINT fk_cpm_distance_pipeline FOREIGN KEY (distance_pipeline_id)
        REFERENCES pipelines (id) ON DELETE CASCADE,
    CONSTRAINT fk_cpm_baseline_pillar FOREIGN KEY (baseline_pillar_id)
        REFERENCES pillars (id) ON DELETE CASCADE,
    CONSTRAINT fk_cpm_distance_pillar FOREIGN KEY (distance_pillar_id)
        REFERENCES pillars (id) ON DELETE CASCADE,
    CONSTRAINT ck_cpm_source CHECK (source IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_cpm_one_side CHECK (baseline_pillar_id IS NOT NULL
                                      OR distance_pillar_id IS NOT NULL)
);

-- Each pillar appears at most once per pair, on its own side.
CREATE UNIQUE INDEX uq_cpm_baseline_pillar
    ON comparison_pillar_mappings (baseline_pipeline_id, distance_pipeline_id, baseline_pillar_id)
    WHERE baseline_pillar_id IS NOT NULL;
CREATE UNIQUE INDEX uq_cpm_distance_pillar
    ON comparison_pillar_mappings (baseline_pipeline_id, distance_pipeline_id, distance_pillar_id)
    WHERE distance_pillar_id IS NOT NULL;
CREATE INDEX ix_cpm_pair
    ON comparison_pillar_mappings (baseline_pipeline_id, distance_pipeline_id);

-- 3 ── the stored comparison object -------------------------------------------
-- GDPR: user_id CASCADEs with the users row — the comparison is scores ABOUT
-- that founder and nobody else, so it dies with them. Exported as
-- distance_comparisons in PersonalDataRepository. Submission FKs CASCADE for
-- the same reason (a comparison over erased submissions is meaningless).

CREATE TABLE founder_comparisons (
    id                     uuid          NOT NULL DEFAULT gen_random_uuid(),
    cohort_id              uuid          NOT NULL,
    org_id                 uuid          NOT NULL,
    user_id                uuid          NOT NULL,
    baseline_submission_id uuid          NOT NULL,
    distance_submission_id uuid          NOT NULL,
    overall_before         numeric(5, 2),
    overall_after          numeric(5, 2),
    overall_delta          numeric(5, 2),
    overall_band_key       text,
    overall_band_label     text,
    config_snapshot        jsonb,
    computed_at            timestamptz   NOT NULL,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_founder_comparisons PRIMARY KEY (id),
    CONSTRAINT uq_founder_comparisons_cohort_user UNIQUE (cohort_id, user_id),
    CONSTRAINT fk_founder_comparisons_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_founder_comparisons_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_founder_comparisons_baseline_submission FOREIGN KEY (baseline_submission_id)
        REFERENCES submissions (id) ON DELETE CASCADE,
    CONSTRAINT fk_founder_comparisons_distance_submission FOREIGN KEY (distance_submission_id)
        REFERENCES submissions (id) ON DELETE CASCADE
);

CREATE INDEX ix_founder_comparisons_org_cohort ON founder_comparisons (org_id, cohort_id);
CREATE INDEX ix_founder_comparisons_user ON founder_comparisons (user_id);

-- Per-pillar snapshot rows. Pillar ids deliberately have NO FK: the row is a
-- historical record keyed for identity, and must survive a pillar being
-- deleted from the builder (the name snapshot keeps it readable).
CREATE TABLE founder_comparison_pillars (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    comparison_id        uuid          NOT NULL,
    baseline_pillar_id   uuid,
    distance_pillar_id   uuid,
    pillar_name_snapshot text          NOT NULL,
    state                text          NOT NULL,
    before_pct           numeric(5, 2),
    after_pct            numeric(5, 2),
    delta                numeric(5, 2),
    band_key             text,
    band_label           text,
    maturity_before      text,
    maturity_after       text,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_founder_comparison_pillars PRIMARY KEY (id),
    CONSTRAINT fk_fcp_comparison FOREIGN KEY (comparison_id)
        REFERENCES founder_comparisons (id) ON DELETE CASCADE,
    CONSTRAINT ck_fcp_state CHECK (state IN ('MAPPED', 'NEWLY_MEASURED', 'NOT_REMEASURED'))
);

CREATE INDEX ix_fcp_comparison ON founder_comparison_pillars (comparison_id);
