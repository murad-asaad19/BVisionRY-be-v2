-- Recommendations were removed from the overall-summary AI output contract (see V93,
-- which dropped the recommendations column, and V94, which patched the global
-- prompt_templates fallback). V94 alone is insufficient: EvaluationService
-- .resolveSummaryPrompt() prefers the PER-PIPELINE pipelines.overall_summary_prompt
-- whenever it is non-blank, and only falls back to the global prompt_templates
-- default when that column is empty. Existing pipelines were seeded (V14, later
-- V18) with the same recommendations guidance, so the model is still being asked
-- to produce a field that is no longer persisted.
--
-- Mirror V94's two targeted replacements against the per-pipeline column so the
-- primary prompt source matches the global fallback. Using replace() (rather than
-- overwriting) preserves any admin customisation of the per-pipeline prompt.
UPDATE pipelines
SET overall_summary_prompt = replace(overall_summary_prompt,
        'reference it briefly in relevant areas, explain it fully in recommendations.',
        'reference it briefly in relevant areas, explain it fully in the summary.')
WHERE overall_summary_prompt IS NOT NULL;

UPDATE pipelines
SET overall_summary_prompt = replace(overall_summary_prompt,
        E'\n- Provide prioritized, actionable recommendations',
        '')
WHERE overall_summary_prompt IS NOT NULL;
