-- Five pillar rows carry stray leading/trailing spaces in their name:
-- " GROWTH MINDSET " and "OPEN MINDEDNESS " on both Mindset Readiness
-- Assessment and Mindset Distance Assessment, and "Your Boundaries " on
-- Magic Number Scan. The space is invisible in normal HTML flow but shows up
-- wherever the name is concatenated rather than laid out as a text node — the
-- results PDF, the XLSX exports, and the prompt blocks the model reads.
--
-- Same situation as V195: this is DATA an admin typed into the pipeline
-- builder, so there is nothing in Java or TS to edit, and it cannot be fixed
-- through the UI either. PillarService.update requires a DRAFT pipeline, and
-- transitioning a PUBLISHED pipeline back to DRAFT runs
-- cleanupPipelineAssignments, which hard-deletes every assignment, submission
-- and answer that pipeline owns. All three instruments above are PUBLISHED.
--
-- Trimming cannot break pair matching. ComparisonMappingService.autoMatch keys
-- on normalize() = name.trim().toLowerCase(Locale.ROOT), so the trimmed and
-- untrimmed spellings are the same key both before and after this migration —
-- ComparisonMappingServiceTest.matchesByName_caseInsensitive_andTrimmed pins
-- that. Nothing else treats the name as an identifier: comparison_pillar_-
-- mappings, pillar_course_mappings, auto_enrolments, program_task_pillars and
-- every evaluation row join on the pillar UUID.
--
-- The two snapshot columns move in the same transaction because they are
-- frozen copies of pillars.name taken at compute time (V161/V169) and
-- ComparisonComputeService writes that name verbatim. Trimming pillars.name
-- alone would leave already-computed rows on the untrimmed spelling while
-- every future compute writes the trimmed one, and
-- CohortGrowthSummaryService.accumulate keys its per-pillar aggregate on the
-- raw pillar_name_snapshot string — two spellings would split one pillar into
-- two buckets in the cohort AI summary. On this database both snapshot columns
-- are already clean, and for a structural reason rather than luck: the
-- snapshot takes the DISTANCE pillar's name whenever the pillar was
-- re-measured, and every mapping row here is two-sided against the cleanly
-- named Founder Mindset Assessment. A one-sided "not re-measured" row would
-- have frozen the untrimmed baseline name, which is what these two statements
-- are here to catch.
--
-- ai_call_logs.pillar_name is deliberately NOT touched — append-only audit of
-- what was actually sent to the model.
--
-- Whitespace only. The sibling oddities on the same instrument are wording
-- judgements for the operator, not bugs, and stay exactly as typed: ALL-CAPS
-- vs Title Case, "OPEN MINDEDNESS" vs the "Open-Mindedness" it is manually
-- mapped to, and the space in "FOCUS/ FLOW" and "ENERGY/ MOTIVATION".
--
-- btrim strips U+0020 only; a scan of all three columns for tabs, newlines and
-- NBSP came back empty, so plain btrim is enough. Idempotent and
-- self-limiting: on a re-run the WHERE clause matches nothing.

UPDATE pillars
SET name = btrim(name)
WHERE name <> btrim(name);

UPDATE founder_comparison_pillars
SET pillar_name_snapshot = btrim(pillar_name_snapshot)
WHERE pillar_name_snapshot <> btrim(pillar_name_snapshot);

UPDATE shift_narratives
SET pillar_name_snapshot = btrim(pillar_name_snapshot)
WHERE pillar_name_snapshot <> btrim(pillar_name_snapshot);
