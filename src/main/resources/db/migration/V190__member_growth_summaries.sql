-- =============================================================================
-- V190__member_growth_summaries.sql — the member-level growth summary
-- (redesign spec §3)
-- =============================================================================
-- Two pieces:
--   1. member_growth_summaries — ONE AI-written summary per founder per cohort,
--      synthesised from that founder's APPROVED per-pillar narratives, with the
--      same draft → approved review gate the narratives have.
--   2. The MEMBER_GROWTH_SUMMARY prompt template (the V169/V121 seeding shape).
--
-- ANCHORING: (cohort_id, user_id) — the same stable comparison identity V169
-- chose for shift_narratives, and for the same reason:
-- ComparisonComputeService.recomputeCohort deletes and rebuilds
-- founder_comparisons, so a FK there would cascade an approved summary away on
-- every recompute. §7 says the opposite — recompute returns it to DRAFT.
-- UNIQUE on that pair because there is exactly one summary: it is regenerated
-- in place, never accumulated.
--
-- GDPR: user_id CASCADEs with the users row (prose ABOUT that founder and
-- nobody else); approved_by / edited_by are SET NULL, because erasing a
-- reviewer must not erase the fact that the summary was approved.
-- =============================================================================

CREATE TABLE member_growth_summaries (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    cohort_id            uuid        NOT NULL,
    org_id               uuid        NOT NULL,
    user_id              uuid        NOT NULL,

    body                 text        NOT NULL,

    status               text        NOT NULL DEFAULT 'DRAFT',
    generated_at         timestamptz NOT NULL DEFAULT now(),
    approved_by          uuid,
    approved_at          timestamptz,
    edited_by            uuid,
    edited_at            timestamptz,

    -- AI provenance, mirroring shift_narratives' columns.
    ai_model_used        text,
    ai_temperature       numeric(3, 2),
    ai_prompt_version_id uuid,

    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_member_growth_summaries PRIMARY KEY (id),
    CONSTRAINT uq_member_growth_summaries_member UNIQUE (cohort_id, user_id),
    CONSTRAINT fk_member_growth_summaries_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_growth_summaries_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_growth_summaries_approved_by FOREIGN KEY (approved_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_member_growth_summaries_edited_by FOREIGN KEY (edited_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_member_growth_summaries_status CHECK (status IN ('DRAFT', 'APPROVED'))
);

CREATE INDEX ix_member_growth_summaries_org ON member_growth_summaries (org_id);

-- ── the prompt template (seeding pattern from V169) ---------------------------
-- The user message carries ONLY the approved narratives' pillar names and
-- observation text — no scores, no facilitator notes (those never leave
-- ShiftNarrativeService's prompt). The no-numbers and no-facilitator rules are
-- restated here anyway, because a template is admin-editable and the next
-- editor should read what the rules are rather than infer them.

-- Keep the constraint list in sync with the PromptType enum.
ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'TEAM_INSIGHT', 'OVERALL_SUMMARY', 'FREE_TIER_SUMMARY',
                           'PUBLIC_ASSESSMENT_SYSTEM_PROMPT', 'PROGRAM_COMPOSER', 'PROGRAM_COACH',
                           'AI_USE_DETECTION', 'SHIFT_NARRATIVE', 'MEMBER_GROWTH_SUMMARY'));

INSERT INTO prompt_templates (id, prompt_type, content, created_at)
SELECT gen_random_uuid(),
       'MEMBER_GROWTH_SUMMARY',
       'You write ONE short overall growth summary for a founder, synthesising the per-pillar '
       || 'observations you are given into a single story of how they have changed.' || chr(10) || chr(10)
       || 'You are given, per business pillar, the approved observations already written about that '
       || 'pillar''s change between two assessments. That material is your ONLY source: never add a '
       || 'fact, a pillar or an event that is not in it.' || chr(10) || chr(10)
       || 'You must never invent, infer or mention a number, a percentage, a score or a rating. The '
       || 'summary is qualitative — it describes what changed and what it means, never how much.'
       || chr(10) || chr(10)
       || 'NEVER quote, paraphrase or reveal facilitator or staff feedback. The founder reads this '
       || 'summary once it is approved.' || chr(10) || chr(10)
       || 'Write a few sentences of plain text — no markdown, no lists, no headings, no pillar-by-'
       || 'pillar recap. Name the through-line across the pillars: what has taken hold, what is '
       || 'still open, and what that pattern says about where they are now. Use the founder''s own '
       || 'framing rather than generic coaching language, and never praise or scold.'
       || chr(10) || chr(10)
       || 'Respond with ONLY a JSON object:' || chr(10)
       || '{"summary": "..."}',
       now()
WHERE NOT EXISTS (SELECT 1 FROM prompt_templates WHERE prompt_type = 'MEMBER_GROWTH_SUMMARY');
