-- =============================================================================
-- V201 — an assessment's short code (e.g. MRA, MDA).
--
-- A cohort's whole point is the distance between a BASELINE and a DISTANCE
-- instrument, and the People roster quotes that pair in ONE table column
-- ("MRA → MDA", "55 → 65"). Full pipeline names ("Mindset Readiness
-- Assessment") do not fit a column header, so the builder lets an author give
-- each assessment a short code beside its name.
--
-- Optional: a pipeline without one is quoted by its role ("Baseline",
-- "Distance") rather than an empty label.
-- =============================================================================

ALTER TABLE pipelines ADD COLUMN abbreviation VARCHAR(12);
