-- =============================================================================
-- V174__program_board_checkpoints.sql — per-admin board checkpoint (safety net)
-- =============================================================================
-- Autosave stays: every board edit still lands immediately. What was missing is
-- the way BACK. Opening a cohort's program board stores a snapshot of its
-- curriculum (modules + audience + tasks + fields) so "Revert changes" can
-- throw away everything done in that editing session.
--
-- One checkpoint per (cohort, admin): re-opening the board REPLACES it (that is
-- what makes it a session snapshot rather than an ever-older one), and two
-- admins never clobber each other's safety net.
--
-- payload is the whole curriculum as JSON — a curriculum is tens of rows, a
-- revert is rare, and normalising it into shadow tables would buy nothing but
-- ceremony and a second schema to migrate.
-- =============================================================================
CREATE TABLE program_board_checkpoints (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    cohort_id  uuid        NOT NULL,
    created_by uuid        NOT NULL,
    payload    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_program_board_checkpoints PRIMARY KEY (id),
    CONSTRAINT fk_program_board_checkpoints_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_program_board_checkpoints_user FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_program_board_checkpoints_cohort_admin UNIQUE (cohort_id, created_by)
);
