-- =============================================================================
-- V199 — the SHIFT_NARRATIVE input inventory catches up with three changes to
-- the user message the backend now assembles (operator review 2026-08-19):
--
--   1. PILLAR SCORE line: the before/after percentages are now handed to the
--      model as CONTEXT so it can gauge the size of the shift — the no-numbers
--      output rule is unchanged, so the inventory must say both things or the
--      rule reads as contradicted by the input.
--   2. Submission tables: the grid arrives as "Row 1 (column titles): …"
--      followed by one line per data row, pipe-separated — not one line per
--      cell. The model must know row 1 is the template's header, not an answer.
--      Rows the template pre-filled are marked "(template-prefilled)".
--   3. ABOUT line: an admin-written sentence on what the task is for may
--      precede a task's submission. Staff-authored context, not member work.
--
-- GUARDED exactly as V192/V194: an installation whose admins have edited this
-- template has a prompt_template_revisions row for it, and their wording is
-- theirs — they merge by hand from the release notes.
-- =============================================================================

UPDATE prompt_templates
SET content = replace(
        content,
        'You are given: the pillar name; whether the pillar improved, declined '
            || 'or held steady; the "what''s working" and "what can improve" text '
            || 'from the earlier (BEFORE) and later (AFTER) assessment; and an '
            || 'ACTIVITY section listing the programme tasks tagged to this '
            || 'pillar, each with the founder''s status on it, its dates, what '
            || 'they submitted, and any facilitator feedback on that work.',
        'You are given: the pillar name; whether the pillar improved, declined '
            || 'or held steady; the pillar''s before and after scores (PILLAR '
            || 'SCORE — context for gauging the size of the shift ONLY, never to '
            || 'be repeated in the output); the "what''s working" and "what can '
            || 'improve" text from the earlier (BEFORE) and later (AFTER) '
            || 'assessment; and an ACTIVITY section listing the programme tasks '
            || 'tagged to this pillar, each with the founder''s status on it, its '
            || 'dates, an ABOUT line staff wrote describing what the task is for, '
            || 'what they submitted, and any facilitator feedback on that work. '
            || 'A submitted grid is rendered as a table: its first line, "Row 1 '
            || '(column titles)", is the template''s header — not something the '
            || 'founder wrote — and each following "Row N" line is one row of '
            || 'their answers, pipe-separated in the same column order. A row '
            || 'marked "(template-prefilled)" was seeded by the template, so '
            || 'treat its content as the exercise''s scaffolding rather than the '
            || 'founder''s own words unless they clearly edited it.')
WHERE prompt_type = 'SHIFT_NARRATIVE'
  AND content LIKE '%You are given: the pillar name; whether the pillar improved%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);
