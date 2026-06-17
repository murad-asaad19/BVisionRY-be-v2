-- V92: Dedicated system prompt for public (QR-link) assessments.
--
-- Public assessments previously shared the single SYSTEM_PROMPT row with the
-- internal/member evaluation flow. This adds a PUBLIC_ASSESSMENT_SYSTEM_PROMPT
-- type so admins can tune the public persona/tone independently.

-- Widen prompt_type first: the column is VARCHAR(30) (V6) but the new value
-- 'PUBLIC_ASSESSMENT_SYSTEM_PROMPT' is 31 chars, so an INSERT below would fail
-- with "value too long for type character varying(30)". 64 leaves headroom for
-- future prompt types.
ALTER TABLE prompt_templates ALTER COLUMN prompt_type TYPE VARCHAR(64);

-- Allow the new prompt type alongside ALL existing ones. The previous constraint
-- (V44) already permits OVERALL_SUMMARY and FREE_TIER_SUMMARY, which are seeded by
-- V44 and read at runtime (EvaluationService.resolveSummaryPrompt); they must be
-- retained or Postgres rejects this ADD CONSTRAINT against existing rows and the
-- whole migration aborts. Keep this list in sync with the PromptType enum.
ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'TEAM_INSIGHT', 'OVERALL_SUMMARY', 'FREE_TIER_SUMMARY', 'PUBLIC_ASSESSMENT_SYSTEM_PROMPT'));

-- Seed the public prompt from the current SYSTEM_PROMPT content so behaviour is
-- unchanged until an admin edits it (they then diverge). Idempotent: only seeds
-- when the row is missing. COALESCE guards the (unexpected) case where no
-- SYSTEM_PROMPT row exists yet -- the literal below is an intentionally minimal
-- placeholder for that degenerate path only; the normal path copies the real prompt.
INSERT INTO prompt_templates (id, prompt_type, content, created_at)
SELECT gen_random_uuid(),
       'PUBLIC_ASSESSMENT_SYSTEM_PROMPT',
       COALESCE(
           (SELECT content FROM prompt_templates WHERE prompt_type = 'SYSTEM_PROMPT' LIMIT 1),
           'You are a professional mindset analyst providing honest, encouraging, evidence-based feedback.'
       ),
       now()
WHERE NOT EXISTS (
    SELECT 1 FROM prompt_templates WHERE prompt_type = 'PUBLIC_ASSESSMENT_SYSTEM_PROMPT'
);
