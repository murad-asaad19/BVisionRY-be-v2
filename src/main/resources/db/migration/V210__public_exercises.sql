-- Public exercises: an exercise template can be opened to anonymous respondents
-- through a stable token (the one printed on a QR code), exactly like a public
-- survey. Responses land in their own table rather than exercise_submissions:
-- a public respondent has no login, so the member review loop (comments,
-- resubmit, quality tags) has nobody to talk to and never applies.

ALTER TABLE exercise_templates
    -- The gate. A public link serves the exercise only while this is true AND
    -- the template is PUBLISHED.
    ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE,
    -- Minted the first time an exercise goes public and NEVER cleared, so a
    -- printed QR keeps working across an unpublish/republish.
    ADD COLUMN public_token UUID UNIQUE,
    -- What the public taker asks for, per exercise (NONE/OPTIONAL/REQUIRED).
    ADD COLUMN respondent_name_mode  VARCHAR(20) NOT NULL DEFAULT 'OPTIONAL',
    ADD COLUMN respondent_email_mode VARCHAR(20) NOT NULL DEFAULT 'OPTIONAL';

CREATE TABLE public_exercise_responses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id     UUID NOT NULL REFERENCES exercise_templates(id) ON DELETE CASCADE,
    respondent_name  VARCHAR(200),
    respondent_email VARCHAR(320),
    -- WORKSHEET: block id -> answer, same shape as exercise_submissions.answers.
    answers     JSONB,
    -- SHEET: the ordered rows, each one columnId -> cell. Denormalised into one
    -- document (not exercise_rows) because nothing anchors to a public row:
    -- there are no review comments to keep pointing at it.
    sheet_rows  JSONB,
    -- Abuse forensics only, same fields survey_responses keeps. sha256 of
    -- token + client IP, so an IP is never stored in the clear.
    ip_hash     VARCHAR(64),
    user_agent  VARCHAR(512),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_public_exercise_responses_template
    ON public_exercise_responses (template_id, submitted_at DESC);
