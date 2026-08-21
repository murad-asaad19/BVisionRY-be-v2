-- =============================================================================
-- V200 — the MEMBER_GROWTH_SUMMARY input inventory catches up with the scores
-- now handed to the model as context (operator review 2026-08-19, same decision
-- as V199 made for the per-pillar prompt): each PILLAR heading carries its
-- before → after percentages and an OVERALL line carries the founder's overall
-- shift, so the synthesis can weigh a +40 pillar over a +2 one instead of
-- treating every observation as equal. The no-numbers OUTPUT rule is unchanged.
--
-- GUARDED exactly as V192/V194/V199: an installation whose admins have edited
-- this template has a prompt_template_revisions row for it, and their wording
-- is theirs — they merge by hand from the release notes.
-- =============================================================================

UPDATE prompt_templates
SET content = replace(
        content,
        'You are given, pillar by pillar, the approved observations already '
            || 'written about that pillar''s change between two assessments, each '
            || 'tagged with its kind.',
        'You are given, pillar by pillar, the approved observations already '
            || 'written about that pillar''s change between two assessments, each '
            || 'tagged with its kind. Each PILLAR heading carries the pillar''s '
            || 'before and after scores, and an OVERALL line carries the '
            || 'founder''s overall shift — context for weighing which changes '
            || 'mattered most ONLY, never to be repeated in the output; the '
            || 'NUMBERS rule below still binds.')
WHERE prompt_type = 'MEMBER_GROWTH_SUMMARY'
  AND content LIKE '%You are given, pillar by pillar, the approved observations%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);
