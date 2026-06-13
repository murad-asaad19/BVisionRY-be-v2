-- Selective pillar re-edit feature.
--
-- Lets an admin reopen a subset of pillars on an already-EVALUATED submission
-- so the member can revise their answers, then re-submit and have only the
-- unlocked pillars re-evaluated (with the OverallSummary regenerated across
-- all pillars). The submission moves EVALUATED → PENDING_REEDIT on unlock,
-- and SUBMITTED → EVALUATED on re-submit (the partial re-eval path).
--
-- Three pieces:
--   1. submission_pillar_unlocks — active unlocks only. Rows are inserted
--      on unlock, deleted on relock or after a successful re-eval. Unique
--      on (submission_id, pillar_id) so the same pillar can't be unlocked
--      twice in a row.
--
--   2. pillar_evaluation_history — full snapshot of a PillarEvaluation row
--      taken right before re-eval overwrites it. Mirrors the live table's
--      AI-output and provenance columns so the snapshot is self-contained
--      (no FK back to the deleted/replaced row needed). version_number
--      orders successive snapshots per (submission, pillar).
--
--   3. overall_summary_history — same idea for the singleton OverallSummary
--      row. Snapshotted before the partial re-eval regenerates it.

CREATE TABLE submission_pillar_unlocks (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id         UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    pillar_id             UUID NOT NULL REFERENCES pillars(id) ON DELETE CASCADE,
    unlocked_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    unlocked_by_admin_id  UUID NOT NULL REFERENCES users(id),
    reason                TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_submission_pillar_unlock UNIQUE (submission_id, pillar_id)
);

CREATE INDEX idx_submission_pillar_unlocks_submission
    ON submission_pillar_unlocks (submission_id);


CREATE TABLE pillar_evaluation_history (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id                 UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    pillar_id                     UUID NOT NULL REFERENCES pillars(id),
    -- Mirrored evaluation columns (kept in sync with pillar_evaluations).
    score_percentage              DECIMAL(5, 2) NOT NULL,
    maturity_label                VARCHAR(50) NOT NULL,
    ai_score_means                TEXT,
    ai_whats_working              JSONB,
    ai_what_can_improve           JSONB,
    ai_business_relevance         TEXT,
    ai_model_used                 VARCHAR(100),
    ai_raw_response               TEXT,
    ai_temperature                DECIMAL(5, 2),
    ai_system_prompt_version_id   UUID,
    ai_rubric_snapshot            TEXT,
    ai_evidence                   JSONB,
    self_assessment_gap           INTEGER,
    evaluated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Snapshot metadata.
    version_number                INTEGER NOT NULL,
    archived_at                   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    archived_reason               VARCHAR(32) NOT NULL,
    archived_by_admin_id          UUID REFERENCES users(id),
    created_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pillar_evaluation_history_submission_pillar
    ON pillar_evaluation_history (submission_id, pillar_id, version_number DESC);


CREATE TABLE overall_summary_history (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id                 UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    -- Mirrored summary columns (kept in sync with overall_summaries).
    overall_score_percentage      DECIMAL(5, 2) NOT NULL,
    summary_narrative             TEXT,
    strengths                     JSONB,
    development_areas             JSONB,
    recommendations               JSONB,
    core_pattern                  TEXT,
    moving_forward_narrative      TEXT,
    ai_model_used                 VARCHAR(100),
    ai_temperature                DECIMAL(5, 2),
    ai_system_prompt_version_id   UUID,
    ai_summary_prompt_snapshot    TEXT,
    generated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Snapshot metadata.
    version_number                INTEGER NOT NULL,
    archived_at                   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    archived_reason               VARCHAR(32) NOT NULL,
    archived_by_admin_id          UUID REFERENCES users(id),
    created_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_overall_summary_history_submission
    ON overall_summary_history (submission_id, version_number DESC);
