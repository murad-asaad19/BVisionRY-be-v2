-- Prompt provenance that survives edits.
--
-- Before V116, prompt_templates held one mutable row per PromptType and edits did
-- setContent+save in place. Evaluations persist the producing prompt id in
-- pillar_evaluations/overall_summaries.ai_system_prompt_version_id (set from
-- PromptTemplateResponse.id in OpenRouterChatService). Because that id never changed
-- across edits, a stored evaluation could no longer be tied to the exact prompt text
-- that produced it.
--
-- From V116 every edit APPENDS an immutable prompt_template_revisions row and points
-- prompt_templates.current_revision_id at it. New evaluations store the REVISION id,
-- which resolves to the exact prompt text. Pre-V116 evaluations carry the TEMPLATE id in
-- ai_system_prompt_version_id (still resolves to the template, just not to a point-in-time
-- snapshot). current_revision_id is a soft reference (no FK) — the same modelling used for
-- ai_system_prompt_version_id (V36) — which also avoids a circular FK with the child table.

CREATE TABLE prompt_template_revisions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES prompt_templates(id) ON DELETE CASCADE,
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prompt_template_revisions_template
    ON prompt_template_revisions (template_id, created_at DESC);

ALTER TABLE prompt_templates ADD COLUMN current_revision_id UUID;

-- Seed one revision per existing template from its current content and point the template
-- at it. Deterministic and single-pass (Flyway runs this exactly once): the CTE inserts one
-- revision per template and hands the new ids straight to the UPDATE.
WITH seeded AS (
    INSERT INTO prompt_template_revisions (template_id, content, created_at)
    SELECT id, content, NOW() FROM prompt_templates
    RETURNING id AS revision_id, template_id
)
UPDATE prompt_templates t
SET current_revision_id = s.revision_id
FROM seeded s
WHERE t.id = s.template_id;
