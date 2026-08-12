-- =============================================================================
-- V175__cohort_board_version.sql — one writer for the curriculum, and a version
-- =============================================================================
-- The Curriculum builder holds every edit locally and sends the WHOLE board on
-- Save (spec §13.10). That made `PUT /program/board` the only writer, and left
-- the per-entity endpoints (create module, update task, move task, revert to a
-- checkpoint…) with no caller — so they are gone from the code with this.
--
-- 1. board_version — optimistic concurrency for that one writer. A Save carries
--    the version the board was loaded at; a mismatch means someone else saved in
--    between, and the payload (a COMPLETE board) would silently delete their
--    modules and tasks. That is answered with a 412 instead. Existing cohorts
--    start at 0, which is exactly what an untouched board is worth.
--
-- 2. program_board_checkpoints (V174) is dropped. It backed "Revert changes",
--    which Save/Discard replaced: Discard drops the local draft without a round
--    trip, so a server-side snapshot of "the board as you opened it" is a second
--    way to undo the same work. The restore ENGINE it introduced lives on — it
--    is what applies the whole-board Save (BoardRestoreRepository).
--
-- No data is at stake: a checkpoint was one admin's transient authoring scratch,
-- never referenced by member work.
-- =============================================================================
ALTER TABLE cohorts ADD COLUMN board_version bigint NOT NULL DEFAULT 0;

DROP TABLE IF EXISTS program_board_checkpoints;
