-- V195 and V196 have an ordering gap between them.
--
-- V195 renames "HANDELING OBSTACLES" -> "HANDLING OBSTACLES" with a
-- wildcard-free ILIKE. V196, which runs AFTER it, btrims the stray leading and
-- trailing spaces that V196's own comment documents on these very instruments.
-- A row spelled 'HANDELING OBSTACLES ' therefore matches neither: V195 sees a
-- trailing space and skips it, V196 trims it to 'HANDELING OBSTACLES' — still
-- misspelled, and V195 has already run.
--
-- Applied migrations are immutable, so the fix is to re-run V195's three
-- statements now that the trim has happened. btrim() in the predicate as well,
-- so a row that acquires whitespace later (an admin re-typing the name into the
-- pipeline builder) is still caught by a re-run.
--
-- Everything V195 says about WHY still holds and is not repeated here: the name
-- is admin-typed DATA with nothing in Java or TS to edit; PillarService.update
-- requires a DRAFT pipeline and the DRAFT transition hard-deletes the
-- instrument's assignments, submissions and answers, so the UI cannot do this;
-- both snapshot columns move with pillars.name because
-- CohortGrowthSummaryService aggregates by pillar NAME and two spellings split
-- one pillar into two buckets; and ai_call_logs.pillar_name stays untouched as
-- an append-only audit of what was actually sent to the model.
--
-- Idempotent: on a database where V195 already did the work (no whitespace on
-- the misspelled rows), all three statements match nothing.

UPDATE pillars
SET name = 'HANDLING OBSTACLES'
WHERE btrim(name) ILIKE 'HANDELING OBSTACLES';

UPDATE founder_comparison_pillars
SET pillar_name_snapshot = 'HANDLING OBSTACLES'
WHERE btrim(pillar_name_snapshot) ILIKE 'HANDELING OBSTACLES';

UPDATE shift_narratives
SET pillar_name_snapshot = 'HANDLING OBSTACLES'
WHERE btrim(pillar_name_snapshot) ILIKE 'HANDELING OBSTACLES';
