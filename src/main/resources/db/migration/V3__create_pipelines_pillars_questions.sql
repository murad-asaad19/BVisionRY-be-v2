CREATE TABLE pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    cycle_type VARCHAR(20) NOT NULL DEFAULT 'ONE_TIME' CHECK (cycle_type IN ('ONE_TIME', 'RECURRING')),
    cadence VARCHAR(20) CHECK (cadence IN ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'BIANNUAL', 'ANNUAL')),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE pillars (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    icon_key VARCHAR(100),
    weight DECIMAL(5, 2) NOT NULL DEFAULT 1.00,
    display_order INT NOT NULL DEFAULT 0,
    ai_rubric_instructions TEXT,
    maturity_thresholds_json JSONB NOT NULL DEFAULT '{"Emerging": [0, 59], "Strong": [60, 79], "Elite": [80, 100]}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pillars_pipeline ON pillars (pipeline_id);

CREATE TABLE questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pillar_id UUID NOT NULL REFERENCES pillars(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL CHECK (type IN ('FREE_TEXT', 'LIKERT', 'MULTIPLE_CHOICE')),
    prompt_text TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    weight DECIMAL(5, 2) NOT NULL DEFAULT 1.00,
    config_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_questions_pillar ON questions (pillar_id);
