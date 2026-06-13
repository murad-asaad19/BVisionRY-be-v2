-- Public assessment links: admin-published QR/link entry points that let
-- anonymous respondents take a PUBLISHED pipeline without an account.
-- Responses reuse the existing submissions/answers/evaluation tables:
-- assignment_id/user_id become nullable and a public submission hangs off
-- public_link_id instead, authenticated by a per-session access_token secret
-- (same trust model as surveys.public_token).

CREATE TABLE public_assessment_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token UUID NOT NULL UNIQUE,
    pipeline_id UUID NOT NULL REFERENCES pipelines(id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')),
    respondent_email_mode VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (respondent_email_mode IN ('NONE', 'OPTIONAL', 'REQUIRED')),
    respondent_name_mode VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (respondent_name_mode IN ('NONE', 'OPTIONAL', 'REQUIRED')),
    show_results_to_respondent BOOLEAN NOT NULL DEFAULT TRUE,
    max_responses INT,
    response_count INT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
-- Token lookups are served by the UNIQUE constraint's implicit index;
-- pipeline_id needs an explicit one for the FK join.
CREATE INDEX idx_public_assessment_links_pipeline ON public_assessment_links(pipeline_id);

-- Submissions grow an alternative anchor: either an org assignment (member
-- flow) or a public link (anonymous flow) — never neither. No ON DELETE
-- action on public_link_id: link deletion is blocked in the service while
-- submissions exist.
ALTER TABLE submissions
    ALTER COLUMN assignment_id DROP NOT NULL,
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN public_link_id UUID REFERENCES public_assessment_links(id),
    ADD COLUMN respondent_email VARCHAR(255),
    ADD COLUMN respondent_name VARCHAR(255),
    ADD COLUMN access_token UUID UNIQUE,
    ADD CONSTRAINT submissions_anchor_check
        CHECK (assignment_id IS NOT NULL OR public_link_id IS NOT NULL);

-- access_token lookups are served by the UNIQUE constraint's implicit index.
-- Partial index: virtually all existing rows are member submissions with a
-- NULL public_link_id.
CREATE INDEX idx_submissions_public_link ON submissions(public_link_id)
    WHERE public_link_id IS NOT NULL;
