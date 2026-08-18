-- BUG-02: the pillar reads "HANDELING OBSTACLES" on the org Full Profile pillar
-- list, the member results screen and the generated results PDF.
--
-- It is DATA, not a constant. No migration ever inserted a pillar name (only the
-- system "General Information" pillar in V30); these rows were typed by an admin
-- through the pipeline builder, so there is nothing in Java or TS to edit.
--
-- Why a migration rather than an admin edit: PillarService.update requires a
-- DRAFT pipeline, and transitioning a PUBLISHED pipeline to DRAFT runs
-- cleanupPipelineAssignments, which hard-deletes every assignment, submission
-- and answer that pipeline owns. Renaming a pillar through the UI on a live
-- instrument would destroy the instrument's data.
--
-- Three tables, one transaction:
--   1. pillars.name             — the live read (profile, results screen, PDF)
--   2. founder_comparison_pillars.pillar_name_snapshot
--   3. shift_narratives.pillar_name_snapshot
-- The two snapshot columns are frozen copies taken at compute time (V161/V169),
-- so a rename of pillars.name alone would never reach an already-computed
-- comparison. They are not cosmetic: CohortGrowthSummaryService aggregates by
-- pillar NAME, so a mix of old and new spellings splits one pillar into two
-- buckets in the cohort AI summary.
--
-- Both misspelled pillar rows (Mindset Readiness + Mindset Distance) are renamed
-- together on purpose. ComparisonMappingService.autoMatch pairs baseline to
-- distance pillars on trim/lowercase of the name; fixing only one side would
-- leave a future cohort unable to auto-pair. Fixing both makes them match
-- "Handling Obstacles" on Founder Mindset Assessment, which is already spelled
-- correctly — this migration improves auto-matching rather than breaking it.
-- Nothing keys on the name as an identifier: every join and mapping row uses the
-- pillar UUID.
--
-- ai_call_logs.pillar_name is deliberately NOT touched — append-only audit of
-- what was actually sent to the model.
--
-- ILIKE (not =) so a stray casing variant is caught too. Idempotent: re-running
-- matches nothing.

UPDATE pillars
SET name = 'HANDLING OBSTACLES'
WHERE name ILIKE 'HANDELING OBSTACLES';

UPDATE founder_comparison_pillars
SET pillar_name_snapshot = 'HANDLING OBSTACLES'
WHERE pillar_name_snapshot ILIKE 'HANDELING OBSTACLES';

UPDATE shift_narratives
SET pillar_name_snapshot = 'HANDLING OBSTACLES'
WHERE pillar_name_snapshot ILIKE 'HANDELING OBSTACLES';
