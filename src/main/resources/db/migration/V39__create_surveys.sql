-- Surveys: lightweight feedback-collection feature separate from Pipeline/Assessment.
-- No AI, no scoring, no rubrics. SUPER_ADMIN authors; responses are optionally public.

CREATE TABLE surveys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    public_token UUID UNIQUE,
    published_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    respondent_identity_mode VARCHAR(20) NOT NULL DEFAULT 'ANONYMOUS'
        CHECK (respondent_identity_mode IN ('ANONYMOUS', 'EMAIL_OPTIONAL', 'EMAIL_REQUIRED')),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_surveys_status ON surveys(status);
CREATE INDEX idx_surveys_public_token ON surveys(public_token) WHERE public_token IS NOT NULL;

CREATE TABLE survey_pillars (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    survey_id UUID NOT NULL REFERENCES surveys(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_survey_pillars_survey ON survey_pillars(survey_id, display_order);

CREATE TABLE survey_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pillar_id UUID NOT NULL REFERENCES survey_pillars(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL
        CHECK (type IN ('SHORT_TEXT', 'LONG_TEXT', 'SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'SCALE')),
    prompt_text TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    config_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_survey_questions_pillar ON survey_questions(pillar_id, display_order);

CREATE TABLE survey_responses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    survey_id UUID NOT NULL REFERENCES surveys(id) ON DELETE CASCADE,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    source VARCHAR(30) NOT NULL
        CHECK (source IN ('PUBLIC_LINK', 'POST_ASSESSMENT')),
    respondent_email VARCHAR(255),
    respondent_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ip_hash VARCHAR(64),
    cookie_id VARCHAR(64),
    user_agent VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_survey_responses_survey_submitted ON survey_responses(survey_id, submitted_at DESC);
CREATE INDEX idx_survey_responses_dedup ON survey_responses(survey_id, cookie_id, ip_hash);

CREATE TABLE survey_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    response_id UUID NOT NULL REFERENCES survey_responses(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES survey_questions(id),
    response_text TEXT,
    selected_value VARCHAR(500),
    selected_values_json JSONB,
    numeric_value DECIMAL(10, 2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_survey_answers_response ON survey_answers(response_id);
CREATE INDEX idx_survey_answers_question ON survey_answers(question_id);

-- Pipeline post-completion pairing: optional link shown to members on assessment
-- completion page. Exactly one of (survey_id, external_url) may be set.
ALTER TABLE pipelines
    ADD COLUMN post_completion_survey_id UUID REFERENCES surveys(id) ON DELETE SET NULL,
    ADD COLUMN post_completion_external_url TEXT,
    ADD COLUMN post_completion_label VARCHAR(120),
    ADD COLUMN post_completion_prominent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT pipelines_post_completion_xor CHECK (
        NOT (post_completion_survey_id IS NOT NULL AND post_completion_external_url IS NOT NULL)
    );
