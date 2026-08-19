-- =============================================================================
-- V202 — REGRESSED and EMERGED complete the before → after taxonomy.
--
-- Every narrative kind is exactly one transition between a "strength" and a
-- "growth edge", which makes the vocabulary a 3x3 matrix. Two cells had no
-- kind, and both of them are the negative ones:
--
--   BEFORE \ AFTER   Strength          Growth edge   Not present
--   Strength         CARRIED_FORWARD   (none)        FADED
--   Growth edge      RESOLVED          PERSISTED     RESOLVED
--   Not present      NEW               (none)        —
--
-- With nowhere to put them the model filed both cases wrongly. Every FADED in
-- the staging data is actually Strength → Growth edge ("previously 'genuinely
-- low' phone distraction … now becoming your 'highest external distraction'"),
-- so the UI rendered "Strength → Not present" beside prose saying the opposite.
-- The Not present → Growth edge cell was punted on purpose: the prompt told the
-- model to carry it into "closingAction" instead, which buried a new problem
-- inside a next step.
--
-- FADED is tightened at the same time, mirroring how NEW already disambiguates
-- itself against RESOLVED. Without that line FADED stays the catch-all it
-- became — the new kind only helps if the old one stops absorbing its cases.
--
-- GUARDED exactly as V192/V194/V199/V200: an installation whose admins have
-- edited this template has a prompt_template_revisions row for it, and their
-- wording is theirs — they merge by hand from the release notes.
--
-- The line separator is taken from the content itself (`(\r?\n)` captured and
-- replayed as `\1`) rather than assumed: V192/V193 seeded these prompts from
-- CRLF files, so a hardcoded chr(10) would splice a lone LF into a CRLF body.
-- =============================================================================

UPDATE prompt_templates
-- 4. EMERGED joins the list, in the slot the punt-line used to hold.
SET content = regexp_replace(
        -- 3. …and the punt-line goes: the case now has a kind.
        regexp_replace(
            -- 2. REGRESSED joins the list, and FADED hands its overflow over.
            regexp_replace(
                -- 1. Five kinds became seven.
                replace(content,
                        'Each is EXACTLY ONE of five kinds.',
                        'Each is EXACTLY ONE of seven kinds.'),
                '(\r?\n)- FADED — a strength in BEFORE that is absent from AFTER\.',
                '\1- REGRESSED — a strength in BEFORE that appears in AFTER as a '
                    || 'growth edge.'
                    || '\1- FADED — a strength in BEFORE that is absent from AFTER. If it '
                    || 'appears there as a growth edge, it is REGRESSED, not FADED.'),
            '\r?\nA growth edge that appears only in AFTER has no kind — do not force it '
                || 'into one\. Carry it into "closingAction" instead\.',
            ''),
        '(\r?\n)A kind you have no evidence for is simply left out',
        '\1- EMERGED — a growth edge in AFTER with no equivalent in BEFORE, neither as a '
            || 'strength nor as a growth edge.'
            || '\1A kind you have no evidence for is simply left out')
WHERE prompt_type = 'SHIFT_NARRATIVE'
  -- EVERY anchor is guarded, not just the count. The four edits above are
  -- independent regexp_replace calls, and a no-match returns its input
  -- unchanged rather than failing — so guarding only edit 1 would let a prompt
  -- whose wording drifted have "five" rewritten to "seven" while keeping five
  -- definitions. All four or none.
  AND content LIKE '%EXACTLY ONE of five kinds.%'
  AND content LIKE '%- FADED — a strength in BEFORE that is absent from AFTER.%'
  AND content LIKE '%A growth edge that appears only in AFTER has no kind — do not force it into one. Carry it into "closingAction" instead.%'
  AND content LIKE '%A kind you have no evidence for is simply left out%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

-- The member-level synthesis restates the same vocabulary in its own wording
-- (V193), so it needs the same two kinds or it can only classify a founder's
-- overall change into five of the seven its inputs are tagged with.
UPDATE prompt_templates
SET content = regexp_replace(
        regexp_replace(
            replace(content,
                    'Each is EXACTLY ONE of five kinds:',
                    'Each is EXACTLY ONE of seven kinds:'),
            '(\r?\n)- FADED — a strength named earlier that no longer appears\.',
            '\1- REGRESSED — a strength named earlier that appears in the later readings as '
                || 'a growth edge.'
                || '\1- FADED — a strength named earlier that no longer appears. If it appears '
                || 'there as a growth edge, it is REGRESSED, not FADED.'),
        '(\r?\n)Write one to three observations per kind at most',
        '\1- EMERGED — a growth edge in the later readings with no equivalent earlier, neither '
            || 'as a strength nor as a growth edge.'
            || '\1Write one to three observations per kind at most')
WHERE prompt_type = 'MEMBER_GROWTH_SUMMARY'
  -- Same rule as above: both anchors guarded, so the count and the definitions
  -- can never disagree.
  AND content LIKE '%EXACTLY ONE of five kinds:%'
  AND content LIKE '%- FADED — a strength named earlier that no longer appears.%'
  AND content LIKE '%Write one to three observations per kind at most%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

-- The legacy single-kind column: V189 moved the breakdown into `items` and new
-- rows leave `kind` NULL, so this CHECK passes vacuously today. It is replaced
-- anyway — a constraint that names five kinds is a trap for whoever next writes
-- a backfill or a fixture against the legacy column.
ALTER TABLE shift_narratives DROP CONSTRAINT IF EXISTS ck_shift_narratives_kind;
ALTER TABLE shift_narratives ADD CONSTRAINT ck_shift_narratives_kind CHECK (kind IN
    ('RESOLVED', 'CARRIED_FORWARD', 'NEW', 'PERSISTED', 'FADED', 'REGRESSED', 'EMERGED'));
