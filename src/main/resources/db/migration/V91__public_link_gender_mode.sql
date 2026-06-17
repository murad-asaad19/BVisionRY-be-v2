-- Per-link control over the pipeline's system Gender question for public
-- assessments. The pipeline (and its logged-in/Team-Insights uses) is never
-- touched; this mode is applied only when the assessment is taken through the
-- public link:
--   NONE     -> Gender hidden (not rendered, never required)
--   OPTIONAL -> Gender shown, skippable
--   REQUIRED -> Gender shown, an answer is required
--
-- Default OPTIONAL preserves the current behaviour (Gender visible but optional,
-- per V90). Reuses the RespondentFieldMode enum that already backs the link's
-- respondent email/name capture modes.
ALTER TABLE public_assessment_links
    ADD COLUMN IF NOT EXISTS gender_mode VARCHAR(16) NOT NULL DEFAULT 'OPTIONAL';
