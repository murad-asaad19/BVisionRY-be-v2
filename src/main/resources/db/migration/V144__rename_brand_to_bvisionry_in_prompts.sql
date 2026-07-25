-- Brand casing changed from "BVisionRY" to "Bvisionry". V12 baked the old casing into
-- the SYSTEM_PROMPT content ("a professional mindset analyst for BVisionRY"), and V92
-- seeded PUBLIC_ASSESSMENT_SYSTEM_PROMPT by copying that same content, so the model is
-- still being told the old company name and echoes it into generated report copy.
--
-- Migrations cannot be edited once applied, so this patches the live rows instead.
-- replace() rather than an overwrite, so any admin customisation of the prompt survives.

-- 1. The live prompt rows. PromptTemplateService.getActivePromptContent() reads
--    prompt_templates.content directly (revisions are provenance only), so this is the
--    text the model actually receives. Matched on content, not prompt_type, to also
--    catch any other type an admin pasted the brand name into.
UPDATE prompt_templates
SET content = replace(content, 'BVisionRY', 'Bvisionry')
WHERE content LIKE '%BVisionRY%';

-- 2. Keep V116's invariant: every content change APPENDS an immutable revision and
--    repoints current_revision_id at it. Without this, current_revision_id would resolve
--    to stale text, so evaluations run after this migration would record provenance that
--    no longer matches the prompt they were produced with. Existing revisions are
--    deliberately left alone — they are the point-in-time record of what older
--    evaluations really saw.
--    Selected generically ("current revision differs from content"), which after step 1
--    covers both the rows step 1 rewrote AND pre-existing drift: V134 rewrote
--    TEAM_INSIGHT's content via SQL without appending a revision, leaving its
--    current_revision_id pointing at the pre-V134 text. Kept as a separate statement
--    because updating prompt_templates in both a data-modifying CTE and its outer
--    statement is undefined behaviour in Postgres.
WITH caught_up AS (
    INSERT INTO prompt_template_revisions (template_id, content, created_at)
    SELECT t.id, t.content, NOW()
    FROM prompt_templates t
    JOIN prompt_template_revisions r ON r.id = t.current_revision_id
    WHERE t.content <> r.content
    RETURNING id AS revision_id, template_id
)
UPDATE prompt_templates t
SET current_revision_id = c.revision_id
FROM caught_up c
WHERE t.id = c.template_id;

-- 3. Per-pipeline prompt overrides. EvaluationService.resolveSummaryPrompt() prefers
--    these over the global prompt_templates fallback whenever non-blank (see V95), so a
--    pipeline an admin pasted the brand name into would otherwise keep the old casing.
--    Seeded pipelines never contained it; these are no-ops unless someone typed it.
UPDATE pipelines
SET overall_summary_prompt = replace(overall_summary_prompt, 'BVisionRY', 'Bvisionry')
WHERE overall_summary_prompt LIKE '%BVisionRY%';

UPDATE pipelines
SET free_tier_prompt = replace(free_tier_prompt, 'BVisionRY', 'Bvisionry')
WHERE free_tier_prompt LIKE '%BVisionRY%';

-- 4. Admin-saved email overrides. EmailTemplateDefaults/EmailTemplateSchemaRegistry were
--    renamed in code, but those are only the fallback — a saved override in
--    email_templates wins and would still render the old casing.
UPDATE email_templates
SET field_values = replace(field_values::text, 'BVisionRY', 'Bvisionry')::jsonb
WHERE field_values::text LIKE '%BVisionRY%';

UPDATE email_templates
SET subject = replace(subject, 'BVisionRY', 'Bvisionry')
WHERE subject LIKE '%BVisionRY%';
