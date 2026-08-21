-- =============================================================================
-- V189__shift_narrative_breakdown.sql — the per-pillar narrative becomes a
-- multi-item breakdown, and the prompt learns to read programme activity
-- (redesign spec §2).
-- =============================================================================
-- Two pieces:
--   1. shift_narratives gains `items` (jsonb array of {kind, text}); the old
--      single kind + body pair goes NULLABLE.
--   2. The seeded SHIFT_NARRATIVE template is rewritten — GUARDED, see below.
--
-- WHY kind/body SURVIVE. Rows written before this migration are approved prose
-- a founder may already have read; rewriting them into items here would be a
-- data migration inventing a classification split nobody made. They keep their
-- old shape, every read path renders both, and a row migrates to `items` the
-- next time it is generated or edited. The CHECK is what makes "both shapes,
-- never neither" a schema fact rather than a convention: a row must carry
-- items, or the legacy pair, and a row with nothing at all cannot exist.
-- =============================================================================

ALTER TABLE shift_narratives ADD COLUMN items jsonb;
ALTER TABLE shift_narratives ALTER COLUMN kind DROP NOT NULL;
ALTER TABLE shift_narratives ALTER COLUMN body DROP NOT NULL;

ALTER TABLE shift_narratives ADD CONSTRAINT ck_shift_narratives_shape
    CHECK (items IS NOT NULL OR (kind IS NOT NULL AND body IS NOT NULL));

-- ── the prompt template ------------------------------------------------------
-- GUARDED on purpose (spec §2 rollout): an installation whose admins have
-- edited this template has a prompt_template_revisions row for it, and their
-- wording is theirs — Flyway must not overwrite it in the night. Those
-- installations merge the new contract by hand from the release notes. An
-- untouched template is still the seed V169 wrote, so replacing it is not an
-- edit, it is a corrected seed.
--
-- The contract this text has to keep, because CODE depends on it:
--   * the five kinds (NarrativeKind) and the {"items":[…],"closingAction":…}
--     envelope (ShiftNarrativeResult + NarrativeGuardrails);
--   * "never state a number" — the founder-visible rule §2 keeps;
--   * "never quote facilitator feedback" — the ACTIVITY section carries staff
--     notes, and an approved narrative is member-visible.
UPDATE prompt_templates
SET content =
       'You write a short qualitative breakdown of how a founder''s work on a single business '
       || 'pillar changed between two assessments.' || chr(10) || chr(10)
       || 'You are given the pillar name; the "what''s working" / "what can improve" text from the '
       || 'earlier (BEFORE) and later (AFTER) assessment; and an ACTIVITY section listing the '
       || 'programme tasks tagged to this pillar — each with the founder''s status on it (done, '
       || 'pending or overdue), its dates, everything the founder submitted, and any facilitator '
       || 'feedback left on that work.' || chr(10) || chr(10)
       || 'You are never given scores, and you must never invent, infer or mention a number, a '
       || 'percentage or a rating — not from the assessments, and not from anything in the '
       || 'ACTIVITY section.' || chr(10) || chr(10)
       || 'NEVER quote, paraphrase or reveal facilitator feedback. It is written by staff for '
       || 'staff, and the founder reads this narrative once it is approved. Use it only to '
       || 'understand the work; the founder must never learn what a facilitator wrote about them.'
       || chr(10) || chr(10)
       || 'Break the change into observations. Each observation is classified as EXACTLY ONE of '
       || 'these five kinds, grounded strictly in the material you were given:' || chr(10)
       || '- RESOLVED: a growth edge named in the BEFORE text that no longer appears, or now '
       || 'appears as a strength.' || chr(10)
       || '- CARRIED_FORWARD: a strength present in both texts — say whether it deepened.' || chr(10)
       || '- NEW: a strength in the AFTER text with no equivalent in the BEFORE text.' || chr(10)
       || '- PERSISTED: a growth edge present in both texts — say whether how it shows up has '
       || 'changed at all.' || chr(10)
       || '- FADED: a strength named in the BEFORE text that no longer appears.' || chr(10) || chr(10)
       || 'Write 1-2 sentences per observation, plain text, second or third person, no markdown, '
       || 'no lists, no headings. Quote or paraphrase the founder''s own wording rather than '
       || 'generic coaching language. Never praise or scold — describe what changed and why it '
       || 'matters. A kind you have no evidence for is simply left out: omit it, never pad it.'
       || chr(10) || chr(10)
       || 'Respond with ONLY a JSON object:' || chr(10)
       || '{"items": [{"kind": "RESOLVED|CARRIED_FORWARD|NEW|PERSISTED|FADED", "text": "..."}], '
       || '"closingAction": "..."}' || chr(10) || chr(10)
       || '"items" must hold at least one observation. "closingAction" is one concrete, '
       || 'forward-looking next step in a single sentence; it is REQUIRED when the pillar '
       || 'declined, and may be an empty string only when the pillar clearly needs no next step.'
WHERE prompt_type = 'SHIFT_NARRATIVE'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);
