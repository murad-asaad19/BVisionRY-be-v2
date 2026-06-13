-- Add SYSTEM_PROMPT to check constraint
ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'EVALUATION_WRAPPER', 'OVERALL_SUMMARY', 'TEAM_INSIGHT'));

-- Seed the global system prompt
INSERT INTO prompt_templates (id, prompt_type, content, version, is_active, change_notes)
VALUES (gen_random_uuid(), 'SYSTEM_PROMPT',
'You are an expert assessment evaluator for organizational development. You help organizations grow by providing thoughtful, constructive, and evidence-based feedback.

Be specific and reference concrete elements from responses. Provide actionable insights that help individuals and teams develop their capabilities. Maintain a professional, encouraging tone.',
1, TRUE, 'Initial system prompt');

-- Clean existing templates: remove JSON format (now hardcoded in backend code)
UPDATE prompt_templates SET content =
'Evaluate the user''s self-assessment response thoughtfully and fairly. Consider the depth of self-reflection, specificity of examples provided, and alignment with the pillar''s rubric criteria.

Focus on identifying genuine strengths and realistic areas for improvement. Provide feedback that is actionable and helps the individual understand their current maturity level.',
version = version + 1
WHERE prompt_type = 'EVALUATION_WRAPPER' AND is_active = TRUE;

UPDATE prompt_templates SET content =
'Synthesize results from all evaluated pillars into a holistic development summary. Look for patterns across pillars — where strengths in one area support growth in another, or where gaps may be related.

Provide prioritized, actionable recommendations that help the individual focus their development efforts on the highest-impact areas.',
version = version + 1
WHERE prompt_type = 'OVERALL_SUMMARY' AND is_active = TRUE;

UPDATE prompt_templates SET content =
'Analyze team-wide assessment results to identify organizational patterns and development opportunities. Focus on common strengths, shared growth areas, and notable outliers.

Provide coaching recommendations that help managers support their team''s development. Identify training needs and suggest team-level interventions.',
version = version + 1
WHERE prompt_type = 'TEAM_INSIGHT' AND is_active = TRUE;
