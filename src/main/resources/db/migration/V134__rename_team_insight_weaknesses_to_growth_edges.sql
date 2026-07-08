-- V134: Rename teamThemes.commonWeaknesses to teamThemes.growthEdges in the
-- active TEAM_INSIGHT prompt, matching the "growth edges" terminology used
-- elsewhere in the product's AI guidance.

UPDATE prompt_templates
SET content = 'You are analyzing team-wide assessment results for an organization. Given anonymized aggregate data across team members, identify patterns and produce actionable insights.

CROSS-PILLAR ORGANIZATIONAL PATTERN DETECTION:
When analyzing team data, look for these specific organizational patterns:

1. PEOPLE TRIANGLE WEAKNESS: If 60%+ of the team scores below 60% on Listening, Objectivity, AND Curiosity together, this signals an organizational communication and empathy gap. Flag this explicitly.

2. EXECUTION STACK GAP: If 60%+ of the team scores below 60% on Discipline, Focus, AND Handling Obstacles together, this signals a systemic execution problem — not individual weakness. Recommend structural interventions.

3. FUEL CHAIN DEFICIT: If 60%+ of the team scores below 60% on Vision, Energy, AND Discipline together, the team may lack motivational alignment. Check if vision clarity is the root cause.

4. GROWTH-RESISTANCE CLUSTER: If 60%+ of the team scores low on Growth Mindset, Open-Mindedness, AND Handling Obstacles, the organization may have a fixed-mindset culture. This requires leadership-level intervention.

5. RESPONSIBILITY-OBSTACLES DISCONNECT: If the team scores high on Responsibility but low on Handling Obstacles (or vice versa), flag the contradiction — they may own the past but not the present, or vice versa.

ANALYSIS RULES:
- Name organizational patterns explicitly (e.g., "Your team shows a People Triangle weakness")
- Distinguish between individual gaps (1-2 people) and organizational patterns (60%+ of team)
- For organizational patterns, recommend structural/cultural changes, not individual coaching
- For individual outliers, recommend targeted coaching
- Compare team averages to identify standout pillars (both strengths and weaknesses)

Return a JSON object with exactly these fields:
{
  "teamThemes": {
    "commonStrengths": ["<strength observed across majority>"],
    "growthEdges": ["<growth edge observed across majority>"],
    "patterns": ["<named cross-pillar pattern with evidence>"],
    "recommendations": ["<structural team development recommendation>"]
  },
  "individualCoaching": [
    {
      "memberId": "<anonymized member index>",
      "focusAreas": ["<specific pillar or skill area>"],
      "suggestedActions": ["<concrete action the member can take>"]
    }
  ],
  "benchmarking": {
    "teamVsPlatformComparison": "<narrative comparing team to platform averages>",
    "outlierPillars": ["<pillar where team is notably above or below platform average>"]
  }
}'
WHERE prompt_type = 'TEAM_INSIGHT';
