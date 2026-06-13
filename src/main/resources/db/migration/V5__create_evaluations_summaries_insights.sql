CREATE TABLE pillar_evaluations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    pillar_id UUID NOT NULL REFERENCES pillars(id),
    score_percentage DECIMAL(5, 2) NOT NULL,
    maturity_label VARCHAR(50) NOT NULL,
    ai_score_means TEXT,
    ai_whats_working JSONB,
    ai_what_can_improve JSONB,
    ai_business_relevance TEXT,
    ai_model_used VARCHAR(100),
    prompt_version_id UUID,
    ai_raw_response TEXT,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pillar_evaluations_submission ON pillar_evaluations (submission_id);
CREATE INDEX idx_pillar_evaluations_pillar_score ON pillar_evaluations (pillar_id, score_percentage);

CREATE TABLE overall_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
    overall_score_percentage DECIMAL(5, 2) NOT NULL,
    summary_narrative TEXT,
    strengths JSONB,
    development_areas JSONB,
    recommendations JSONB,
    ai_model_used VARCHAR(100),
    prompt_version_id UUID,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE insight_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    pipeline_id UUID NOT NULL REFERENCES pipelines(id),
    cycle_number INT NOT NULL DEFAULT 1,
    report_json JSONB NOT NULL,
    ai_model_used VARCHAR(100),
    prompt_version_id UUID,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_insight_reports_org ON insight_reports (organization_id, pipeline_id);
