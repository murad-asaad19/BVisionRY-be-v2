-- V44: Move overall-summary and free-tier-summary prompt defaults to the AI Config page.
--
-- Before this migration: the two prompts lived ONLY per-pipeline (see V14).
-- After this migration: per-pipeline prompts are overrides; when blank the
-- evaluation pipeline falls back to the global defaults seeded here.
--
-- The prompt_templates table already stores system-wide defaults for
-- SYSTEM_PROMPT and TEAM_INSIGHT. We re-introduce OVERALL_SUMMARY and add
-- FREE_TIER_SUMMARY alongside them.

ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'TEAM_INSIGHT', 'OVERALL_SUMMARY', 'FREE_TIER_SUMMARY'));

INSERT INTO prompt_templates (id, prompt_type, content)
SELECT gen_random_uuid(), 'OVERALL_SUMMARY',
'Synthesize results from all evaluated pillars into a holistic development summary.

CROSS-PILLAR ANALYSIS RULES:
1. If the same gap appears in 3 or more pillars, it is ONE core pattern — name it once clearly, reference it briefly in relevant areas, explain it fully in recommendations.
2. Look for the "strong with systems, developing with people" pattern: high scores in data/process pillars (Objectivity, Focus, Discipline) but lower in people pillars (Listening, Curiosity, Open-Mindedness).
3. Check for contradictions: a perfect Objectivity exercise score but vague Obstacle descriptions means the skill exists on paper but is not applied in real business.
4. Vision Clarity affects everything: weak vision often connects to poor discipline (no clear destination = no clear daily priorities) and low motivation fuel.
5. How they describe obstacles tells you about their Responsibility score. External blame in obstacles = low accountability.
6. Missing emotional cues in Listening often predicts assumption-making in Objectivity.

SUMMARY PRINCIPLES:
- Gaps are called "growth edges" — not weaknesses
- Build on existing foundations — they already know a lot
- Connect mindset patterns to real business outcomes
- Provide prioritized, actionable recommendations
- Name the core pattern first, then detail specific areas'
WHERE NOT EXISTS (
    SELECT 1 FROM prompt_templates WHERE prompt_type = 'OVERALL_SUMMARY'
);

INSERT INTO prompt_templates (id, prompt_type, content)
SELECT gen_random_uuid(), 'FREE_TIER_SUMMARY',
'Provide a brief, encouraging summary of the assessment responses. Highlight the top 3 strengths observed across all areas. Give a high-level maturity indication without detailed pillar scores. End with a teaser about what the full Premium report reveals.'
WHERE NOT EXISTS (
    SELECT 1 FROM prompt_templates WHERE prompt_type = 'FREE_TIER_SUMMARY'
);
