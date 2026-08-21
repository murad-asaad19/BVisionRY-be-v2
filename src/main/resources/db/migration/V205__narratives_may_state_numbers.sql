-- =============================================================================
-- V205 — the no-numbers output rule is retired (operator decision 2026-08-20):
-- member-facing narratives MAY now repeat the scores and figures they are
-- given. What remains is the anti-hallucination half — never state a figure
-- the material does not contain — the same rule the staff-facing cohort
-- prompt has always carried.
--
-- Touches SHIFT_NARRATIVE and MEMBER_GROWTH_SUMMARY: the NUMBERS section of
-- each, plus the "never to be repeated in the output" clauses V199/V200 put in
-- their input inventories.
--
-- GUARDED exactly as V192/V194/V199/V200: an installation whose admins have
-- edited a template has a prompt_template_revisions row for it, and their
-- wording is theirs — they merge by hand from the release notes.
-- =============================================================================

-- SHIFT_NARRATIVE: input inventory (V199 wording)
UPDATE prompt_templates
SET content = replace(
        content,
        $old$(PILLAR SCORE — context for gauging the size of the shift ONLY, never to be repeated in the output)$old$,
        $new$(PILLAR SCORE — context for gauging the size of the shift)$new$)
WHERE prompt_type = 'SHIFT_NARRATIVE'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

-- SHIFT_NARRATIVE: the NUMBERS rule (V192 wording)
UPDATE prompt_templates
SET content = replace(
        content,
        $old$The material you are given contains scores, percentages, ratings, frequencies and durations. Never repeat one, and never state, invent or infer a number, percentage, score or rating of your own. Express every quantity in words — "most days", "roughly half", "for over a year". This holds even when you are quoting the founder: keep their phrasing, drop their figures.$old$,
        $new$The material you are given contains scores, percentages, ratings, frequencies and durations. You may repeat them when a figure makes an observation concrete. Never state a figure the material does not contain: never invent, infer or compute one.$new$)
WHERE prompt_type = 'SHIFT_NARRATIVE'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

-- MEMBER_GROWTH_SUMMARY: input inventory (V200 wording)
UPDATE prompt_templates
SET content = replace(
        content,
        $old$ — context for weighing which changes mattered most ONLY, never to be repeated in the output; the NUMBERS rule below still binds.$old$,
        $new$ — context for weighing which changes mattered most.$new$)
WHERE prompt_type = 'MEMBER_GROWTH_SUMMARY'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

-- MEMBER_GROWTH_SUMMARY: the NUMBERS rule (V193 wording)
UPDATE prompt_templates
SET content = replace(
        content,
        $old$Never state, invent or infer a number, percentage, score or rating. Express every quantity in words — "most pillars", "roughly half", "the majority of your work".$old$,
        $new$You may repeat the scores you are given when a figure makes an observation concrete. Never state a figure the material does not contain: never invent, infer or compute one.$new$)
WHERE prompt_type = 'MEMBER_GROWTH_SUMMARY'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);
