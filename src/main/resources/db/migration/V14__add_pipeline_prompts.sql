-- Add per-pipeline AI prompts
-- freeTierPrompt: single AI call for free tier orgs (gets ALL answers)
-- overallSummaryPrompt: how to synthesize per-pillar results into holistic summary (Premium)

ALTER TABLE pipelines ADD COLUMN free_tier_prompt TEXT;
ALTER TABLE pipelines ADD COLUMN overall_summary_prompt TEXT;

-- Set defaults for existing pipelines
UPDATE pipelines SET free_tier_prompt =
'Provide a brief, encouraging summary of the assessment responses. Highlight the top 3 strengths observed across all areas. Give a high-level maturity indication without detailed pillar scores. End with a teaser about what the full Premium report reveals.';

UPDATE pipelines SET overall_summary_prompt =
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
- Name the core pattern first, then detail specific areas';
