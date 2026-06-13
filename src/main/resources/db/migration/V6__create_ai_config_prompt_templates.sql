CREATE TABLE ai_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(50) NOT NULL DEFAULT 'OPENROUTER',
    api_key_encrypted TEXT,
    default_evaluation_model VARCHAR(100) NOT NULL DEFAULT 'anthropic/claude-sonnet-4',
    default_insight_model VARCHAR(100) NOT NULL DEFAULT 'anthropic/claude-sonnet-4',
    evaluation_temperature DECIMAL(3, 2) NOT NULL DEFAULT 0.30,
    insight_temperature DECIMAL(3, 2) NOT NULL DEFAULT 0.70,
    max_tokens_evaluation INT NOT NULL DEFAULT 2048,
    max_tokens_insight INT NOT NULL DEFAULT 4096,
    updated_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

INSERT INTO ai_configurations (id, provider) VALUES (gen_random_uuid(), 'OPENROUTER');

CREATE TABLE prompt_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_type VARCHAR(30) NOT NULL CHECK (prompt_type IN ('EVALUATION_WRAPPER', 'OVERALL_SUMMARY', 'TEAM_INSIGHT')),
    content TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id),
    change_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prompt_templates_type_active ON prompt_templates (prompt_type, is_active);

INSERT INTO prompt_templates (id, prompt_type, content, version, is_active, change_notes)
VALUES
(gen_random_uuid(), 'EVALUATION_WRAPPER',
'You are an expert assessment evaluator. Evaluate the user''s response based on the following rubric criteria.

{{RUBRIC_INSTRUCTIONS}}

Return a JSON object with exactly these fields:
{
  "scorePercentage": <integer 0-100>,
  "maturityLabel": "<Emerging|Strong|Elite>",
  "whatThisScoreMeans": "<narrative explanation>",
  "whatsWorking": ["<strength 1>", "<strength 2>"],
  "whatCanImprove": ["<area 1>", "<area 2>"],
  "whyThisMattersForBusiness": "<contextual business relevance>"
}

Be specific, constructive, and evidence-based. Reference concrete elements from the response.',
1, TRUE, 'Initial default evaluation wrapper prompt'),

(gen_random_uuid(), 'OVERALL_SUMMARY',
'You are synthesizing results from a multi-pillar self-assessment. Given the individual pillar scores, maturity labels, and key findings, produce a holistic development summary.

Return a JSON object with exactly these fields:
{
  "overallScorePercentage": <integer 0-100>,
  "summaryNarrative": "<holistic summary>",
  "strengths": ["<strength 1>", "<strength 2>"],
  "developmentAreas": ["<area 1>", "<area 2>"],
  "recommendations": ["<recommendation 1>", "<recommendation 2>"]
}

Consider relationships between pillars. Identify patterns and provide actionable recommendations.',
1, TRUE, 'Initial default overall summary prompt'),

(gen_random_uuid(), 'TEAM_INSIGHT',
'You are analyzing team-wide assessment results. Given anonymized aggregate data across team members, identify patterns and produce actionable insights.

Return a JSON object with exactly these fields:
{
  "teamThemes": {
    "commonStrengths": ["<strength>"],
    "commonWeaknesses": ["<weakness>"],
    "patterns": ["<pattern observation>"],
    "recommendations": ["<team development recommendation>"]
  },
  "individualCoaching": [
    {
      "memberId": "<id>",
      "focusAreas": ["<area>"],
      "suggestedActions": ["<action>"]
    }
  ],
  "benchmarking": {
    "teamVsPlatformComparison": "<narrative>",
    "outlierPillars": ["<pillar where team is notably above/below average>"]
  }
}',
1, TRUE, 'Initial default team insight prompt');
