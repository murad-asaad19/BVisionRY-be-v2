-- Recommendations were removed from the overall-summary AI output contract, so the
-- summary guidance must stop asking the model to produce them (otherwise it wastes
-- output tokens on a field that is no longer persisted). Targeted text replacements
-- keep any unrelated admin customisation of the prompt intact.
UPDATE prompt_templates
SET content = replace(content,
        'reference it briefly in relevant areas, explain it fully in recommendations.',
        'reference it briefly in relevant areas, explain it fully in the summary.')
WHERE prompt_type = 'OVERALL_SUMMARY';

UPDATE prompt_templates
SET content = replace(content,
        E'\n- Provide prioritized, actionable recommendations',
        '')
WHERE prompt_type = 'OVERALL_SUMMARY';
