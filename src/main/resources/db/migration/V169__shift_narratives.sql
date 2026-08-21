-- =============================================================================
-- V169__shift_narratives.sql — Qualitative Shift Narrative (redesign spec §6)
-- =============================================================================
-- Two pieces:
--   1. shift_narratives — one AI-written narrative per founder × mapped pillar,
--      with the draft → approved review gate and §7 config snapshot.
--   2. The SHIFT_NARRATIVE prompt template (the AI Config machinery's seeding
--      pattern, as used by V121/V130).
--
-- ANCHORING DECISION (the important one): the narrative is anchored to
-- (cohort_id, user_id, distance_pillar_id) — NOT to founder_comparisons.id or
-- founder_comparison_pillars.id. ComparisonComputeService.recomputeCohort
-- DELETES the comparison and its pillar rows and rebuilds them, so a FK with
-- ON DELETE CASCADE would silently destroy approved narratives on every
-- recompute — the exact opposite of §7 ("approved narratives never
-- auto-recompute; recompute returns them to draft"). (cohort_id, user_id) is
-- the stable comparison identity (uq_founder_comparisons_cohort_user) and the
-- distance pillar id is the stable pillar identity within a pair
-- (uq_cpm_distance_pillar), so this key survives a rebuild untouched.
--
-- Pillar ids carry NO FK for the same reason founder_comparison_pillars' do:
-- the row is a historical record and must outlive a pillar deleted from the
-- builder (pillar_name_snapshot keeps it readable).
--
-- GDPR: user_id CASCADEs with the users row — the narrative is prose ABOUT
-- that founder and nobody else, so it dies with them (exported as
-- shift_narratives). approved_by / edited_by are SET NULL: a reviewer
-- attribution on the ORG's record, like assignments.assigned_by — erasing the
-- reviewer must not erase the fact that the narrative was approved.
-- =============================================================================

CREATE TABLE shift_narratives (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    cohort_id            uuid        NOT NULL,
    org_id               uuid        NOT NULL,
    user_id              uuid        NOT NULL,
    baseline_pillar_id   uuid        NOT NULL,
    distance_pillar_id   uuid        NOT NULL,
    pillar_name_snapshot text        NOT NULL,

    kind                 text        NOT NULL,
    body                 text        NOT NULL,
    -- The forward-looking next step. Mandatory when `decline` is true (§6),
    -- validated in code rather than by CHECK: the rule binds the model AND the
    -- human reviewer, and only the application sees the edit.
    closing_action       text,
    -- Is this a DECLINING pillar? Derived structurally from the pillar's delta
    -- sign, NEVER from the band key: §7 lets a super admin rename or delete any
    -- band, and a guardrail keyed on the literal string 'decline' would go
    -- silently dead the moment they did. Re-stamped by "Recompute cohort".
    decline              boolean     NOT NULL DEFAULT false,
    -- The shift band label/key in force at generation — DISPLAY ONLY.
    band_key             text,

    status               text        NOT NULL DEFAULT 'DRAFT',
    generated_at         timestamptz NOT NULL DEFAULT now(),
    approved_by          uuid,
    approved_at          timestamptz,
    edited_by            uuid,
    edited_at            timestamptz,

    -- AI provenance, mirroring pillar_evaluations' columns.
    ai_model_used        text,
    ai_temperature       numeric(3, 2),
    ai_prompt_version_id uuid,
    -- §7 snapshot: the narrative wording config + the band label in force at
    -- generation time. Config edits apply forward only.
    config_snapshot      jsonb,

    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_shift_narratives PRIMARY KEY (id),
    CONSTRAINT uq_shift_narratives_pillar UNIQUE (cohort_id, user_id, distance_pillar_id),
    CONSTRAINT fk_shift_narratives_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_shift_narratives_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_shift_narratives_approved_by FOREIGN KEY (approved_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_shift_narratives_edited_by FOREIGN KEY (edited_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_shift_narratives_kind CHECK (kind IN
        ('RESOLVED', 'CARRIED_FORWARD', 'NEW', 'PERSISTED', 'FADED')),
    CONSTRAINT ck_shift_narratives_status CHECK (status IN ('DRAFT', 'APPROVED'))
);

CREATE INDEX ix_shift_narratives_cohort_user ON shift_narratives (cohort_id, user_id);
CREATE INDEX ix_shift_narratives_org ON shift_narratives (org_id);

-- ── the prompt template (seeding pattern from V121 / V130) --------------------
-- The user message carries ONLY the pillar name and the before/after text
-- blocks — never a score. That is enforced by construction in
-- ShiftNarrativeService; the prompt says so too so an editor does not undo it.

-- Keep the constraint list in sync with the PromptType enum.
ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'TEAM_INSIGHT', 'OVERALL_SUMMARY', 'FREE_TIER_SUMMARY',
                           'PUBLIC_ASSESSMENT_SYSTEM_PROMPT', 'PROGRAM_COMPOSER', 'PROGRAM_COACH',
                           'AI_USE_DETECTION', 'SHIFT_NARRATIVE'));

INSERT INTO prompt_templates (id, prompt_type, content, created_at)
SELECT gen_random_uuid(),
       'SHIFT_NARRATIVE',
       'You write one short qualitative narrative about how a founder''s answers on a single '
       || 'business pillar changed between two assessments. You are given ONLY the pillar name and '
       || 'the "what''s working" / "what can improve" text from the earlier (BEFORE) and later '
       || '(AFTER) assessment. You are never given scores, and you must never invent, infer or '
       || 'mention a number, a percentage or a rating.' || chr(10) || chr(10)
       || 'Classify the change into EXACTLY ONE of these five kinds, grounded strictly in the two '
       || 'texts:' || chr(10)
       || '- RESOLVED: an issue named in the BEFORE text is no longer present in the AFTER text.' || chr(10)
       || '- CARRIED_FORWARD: a strength named in the BEFORE text is still present and has deepened.' || chr(10)
       || '- NEW: something in the AFTER text has no equivalent at all in the BEFORE text.' || chr(10)
       || '- PERSISTED: an issue named in the BEFORE text is still present in the AFTER text.' || chr(10)
       || '- FADED: a strength named in the BEFORE text no longer appears in the AFTER text.' || chr(10) || chr(10)
       || 'Write 2-4 sentences, plain text, second or third person, no markdown, no lists, no '
       || 'headings. Quote or paraphrase the founder''s own wording rather than generic coaching '
       || 'language. Never praise or scold — describe what changed and why it matters.' || chr(10) || chr(10)
       || 'Respond with ONLY a JSON object:' || chr(10)
       || '{"kind": "RESOLVED|CARRIED_FORWARD|NEW|PERSISTED|FADED", "narrative": "...", '
       || '"closingAction": "..."}' || chr(10) || chr(10)
       || '"closingAction" is one concrete, forward-looking next step in a single sentence. Leave '
       || 'it as an empty string only when the pillar clearly needs no next step.',
       now()
WHERE NOT EXISTS (SELECT 1 FROM prompt_templates WHERE prompt_type = 'SHIFT_NARRATIVE');
