-- =============================================================================
-- V206 — pre-V203 RESOLVED rows go back through coach review.
--
-- V203 narrowed RESOLVED to its flattering half (growth edge → strength) but
-- only rewrote the PROMPT: every narrative generated BEFORE it could still be
-- carrying a RESOLVED that meant "growth edge that simply stopped being
-- mentioned" — and the founder view celebrates every RESOLVED as "You closed
-- this gap. It is a strength now."
--
-- Code cannot tell the two apart after the fact, and the second reading
-- redesign is explicit about that case: "If the system cannot confidently
-- tell which of the six situations applies, it should not guess… flag it for
-- the coach to review by hand." The review gate IS that flag (V203's own
-- rationale), so approved rows with a pre-V203 RESOLVED item return to DRAFT
-- for a human to re-read under the new meaning — reclassify or re-approve.
-- Draft ones are already in the queue and are left alone.
--
-- Anchored on THIS environment's V203 apply time, so a row generated under
-- the narrowed prompt is never re-flagged. On a fresh install both run in the
-- same session and these tables are empty — a no-op.
-- =============================================================================

UPDATE shift_narratives
SET status = 'DRAFT', approved_by = NULL, approved_at = NULL
WHERE status = 'APPROVED'
  AND (kind = 'RESOLVED' OR items @> '[{"kind": "RESOLVED"}]')
  AND generated_at < (SELECT installed_on FROM flyway_schema_history WHERE version = '203');

-- The member-level summary is synthesised FROM the approved narratives; one
-- describing a pre-V203 RESOLVED story has the same ambiguity and re-earns
-- its stamp the same way.
UPDATE member_growth_summaries
SET status = 'DRAFT', approved_by = NULL, approved_at = NULL
WHERE status = 'APPROVED'
  AND items @> '[{"kind": "RESOLVED"}]'
  AND generated_at < (SELECT installed_on FROM flyway_schema_history WHERE version = '203');
