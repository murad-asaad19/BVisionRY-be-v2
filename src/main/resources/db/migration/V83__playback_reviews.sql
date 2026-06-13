-- =============================================================================
-- V83__playback_reviews.sql — Video resume position + learner course reviews
-- =============================================================================
-- 1. Adds playback-position tracking columns to content_progress.
-- 2. Adds user_id to review for per-user uniqueness (partial index allows legacy
--    seeded reviews that have null user_id).
-- =============================================================================

-- -------------------------------------------------------------------------
-- content_progress: playback position + watch percentage
-- -------------------------------------------------------------------------
ALTER TABLE content_progress
    ADD COLUMN last_position_seconds integer NOT NULL DEFAULT 0,
    ADD COLUMN watched_pct           integer NOT NULL DEFAULT 0;

-- -------------------------------------------------------------------------
-- review: identity of the reviewing learner (nullable for legacy seed rows)
-- -------------------------------------------------------------------------
ALTER TABLE review
    ADD COLUMN user_id uuid;

-- Partial unique index: at most one review per (course, real user).
-- Legacy/seeded rows where user_id IS NULL are excluded from uniqueness.
CREATE UNIQUE INDEX uq_review_course_user
    ON review (course_id, user_id)
    WHERE user_id IS NOT NULL;
