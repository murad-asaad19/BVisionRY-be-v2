-- =============================================================================
-- V191__cohort_growth_summaries.sql — the cohort-level growth summary
-- (redesign spec §4)
-- =============================================================================
-- The staff-facing sibling of the org insight, cohort-scoped: one AI-written
-- report per generation, NO review gate (staff-only, auto-published), history
-- kept. The row shape is insight_reports' — GENERATING → COMPLETED / FAILED
-- with report_json + failure_reason — because the frontend polls, retries and
-- renders it exactly the way it already polls, retries and renders an insight.
--
-- NO user reference anywhere, deliberately: the report is about the COHORT.
-- Coverage is stored as two counters rather than a member list, which is all
-- the "narratives included for 8/12 members" line needs and leaves nothing for
-- a GDPR erasure to chase. include_names records whether real names were sent
-- to the provider for THIS run (§4: any org admin may ask for that), so the
-- decision is auditable after the fact from the row itself.
--
-- cohort_id CASCADEs: a deleted cohort has no summaries to keep.
-- =============================================================================

CREATE TABLE cohort_growth_summaries (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    cohort_id               uuid        NOT NULL,
    org_id                  uuid        NOT NULL,

    status                  text        NOT NULL DEFAULT 'GENERATING',
    report_json             jsonb,
    failure_reason          text,

    include_names           boolean     NOT NULL DEFAULT false,
    members_total           int         NOT NULL,
    members_with_narratives int         NOT NULL,

    ai_model_used           text,
    generated_at            timestamptz NOT NULL DEFAULT now(),

    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_cohort_growth_summaries PRIMARY KEY (id),
    CONSTRAINT fk_cohort_growth_summaries_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT ck_cohort_growth_summaries_status
        CHECK (status IN ('GENERATING', 'COMPLETED', 'FAILED'))
);

-- The list read (newest first) and the "latest" read are the same index.
CREATE INDEX ix_cohort_growth_summaries_cohort
    ON cohort_growth_summaries (cohort_id, generated_at DESC);

-- ── the prompt template (seeding pattern from V169/V190) ----------------------
-- Staff-facing, so unlike the member-facing narratives this one MAY quote the
-- numbers it is given: the before/after table is half of what a facilitator
-- opens the report for. The no-numbers rule is a MEMBER-narrative rule.

-- Keep the constraint list in sync with the PromptType enum.
ALTER TABLE prompt_templates DROP CONSTRAINT IF EXISTS prompt_templates_prompt_type_check;
ALTER TABLE prompt_templates ADD CONSTRAINT prompt_templates_prompt_type_check
    CHECK (prompt_type IN ('SYSTEM_PROMPT', 'TEAM_INSIGHT', 'OVERALL_SUMMARY', 'FREE_TIER_SUMMARY',
                           'PUBLIC_ASSESSMENT_SYSTEM_PROMPT', 'PROGRAM_COMPOSER', 'PROGRAM_COACH',
                           'AI_USE_DETECTION', 'SHIFT_NARRATIVE', 'MEMBER_GROWTH_SUMMARY',
                           'COHORT_GROWTH_SUMMARY'));

INSERT INTO prompt_templates (id, prompt_type, content, created_at)
SELECT gen_random_uuid(),
       'COHORT_GROWTH_SUMMARY',
       'You write ONE growth report for a whole cohort of founders, for the staff who run that '
       || 'cohort. Your reader is a facilitator deciding what to do next with the group.'
       || chr(10) || chr(10)
       || 'You are given three things, and they are your ONLY source — never add a member, a '
       || 'pillar or an event that is not in them:' || chr(10)
       || '1. PER-MEMBER COMPARISON: each member''s before, after, change and band per pillar, '
       || 'plus their overall.' || chr(10)
       || '2. APPROVED NARRATIVES: the human-approved observations already written about each '
       || 'member''s pillars. Members with none simply have none — say nothing about what they '
       || 'might have shown.' || chr(10)
       || '3. PILLAR AGGREGATE: per pillar, the cohort average, the maturity distribution, and '
       || 'the strengths and improvement areas raised across the group.' || chr(10) || chr(10)
       || 'Your job is the COHORT-WIDE pattern: what moved for many of them and why the material '
       || 'suggests it moved, where the group is stuck together, and which pillars split the '
       || 'cohort rather than lifting it. Individual members are worth naming only as evidence of '
       || 'a pattern, never as a per-member roll-call — the staff already have the table.'
       || chr(10) || chr(10)
       || 'Members are identified in the input as "Member 1", "Member 2" and so on unless real '
       || 'names appear there. Use exactly the labels the input uses: never invent a name, and '
       || 'never guess who a numbered member is.' || chr(10) || chr(10)
       || 'You MAY quote the figures you are given — before, after, change, averages — because '
       || 'this report is for staff. Never invent a figure you were not given, and never compute '
       || 'a statistic the input does not contain.' || chr(10) || chr(10)
       || 'Keep every entry short and concrete: plain text, no markdown, no headings, no praise '
       || 'and no scolding. Recommendations are things the facilitator can actually run with '
       || 'this cohort next.' || chr(10) || chr(10)
       || 'Respond with ONLY a JSON object:' || chr(10)
       || '{"overview": "...", "sharedWins": ["..."], "sharedRisks": ["..."], "recommendations": ["..."]}',
       now()
WHERE NOT EXISTS (SELECT 1 FROM prompt_templates WHERE prompt_type = 'COHORT_GROWTH_SUMMARY');
