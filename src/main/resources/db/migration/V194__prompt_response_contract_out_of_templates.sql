-- The JSON response contract stops being prompt text.
--
-- Every narrative prompt ended with an OUTPUT block naming the exact JSON shape
-- ("Respond with ONLY a JSON object: {...}"). That block was editable in the AI
-- config screen, which made the parse contract something a super admin could
-- silently break: rename a key, drop "closingAction", and every generation for
-- that prompt type fails until someone notices.
--
-- It was also redundant. The engine builds each call through a LangChain4j
-- AiService whose method returns a typed record (ShiftNarrativeResult,
-- MemberGrowthSummaryResult, CohortGrowthSummaryResult), and LangChain4j derives
-- the contract from that return type on BOTH paths:
--   * models advertising structured_outputs -> Lc4jChatModelProvider enables
--     RESPONSE_FORMAT_JSON_SCHEMA + strictJsonSchema, so the schema is enforced
--     provider-side and the model cannot return the wrong shape;
--   * models without it -> the AiService appends generated format instructions
--     to the message itself.
-- Either way the shape the code deserializes is the shape the model is asked
-- for, which hand-maintained prompt text can only drift from.
-- StructuredOutputGuardrail still validates and repairs on top of that.
--
-- The SHAPE goes; the RULES stay. "A declining pillar must close with a next
-- step" and "give at least one observation" are behaviour, not schema — they are
-- what NarrativeGuardrails.MISSING_CLOSING_ACTION and EMPTY_NARRATIVE reject on,
-- so the model still has to be told them. They lived in the same paragraph as
-- the schema line and are re-stated below without naming a JSON key.
--
-- Only templates a super admin has NOT edited are touched (same
-- prompt_template_revisions guard V189/V192/V193 seed with) — an edited prompt
-- is theirs, and rewriting it here would discard their wording.

-- 1. Cut the OUTPUT heading and everything from the contract sentence onward.
--    '.' spans newlines in Postgres by default, so this takes the schema line
--    and its trailing notes with it.
UPDATE prompt_templates
SET content = rtrim(regexp_replace(
        content,
        '(\n+OUTPUT)?\n+Respond with ONLY a JSON object:.*$',
        ''))
WHERE prompt_type IN ('SHIFT_NARRATIVE', 'MEMBER_GROWTH_SUMMARY', 'COHORT_GROWTH_SUMMARY')
  AND content LIKE '%Respond with ONLY a JSON object:%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

-- 2. Put the behavioural half back, minus the field names.
UPDATE prompt_templates
SET content = content || chr(10) || chr(10) || 'CLOSING ACTION' || chr(10)
        || 'When PILLAR DIRECTION says the pillar declined, a closing action is '
        || 'MANDATORY: one sentence naming a single concrete next step. Otherwise '
        || 'give one when there is an obvious next step, and leave it empty when '
        || 'there is not.'
WHERE prompt_type = 'SHIFT_NARRATIVE'
  AND content NOT LIKE '%CLOSING ACTION%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

UPDATE prompt_templates
SET content = content || chr(10) || chr(10) || 'COVERAGE' || chr(10)
        || 'Give at least one observation.'
WHERE prompt_type = 'MEMBER_GROWTH_SUMMARY'
  AND content NOT LIKE '%COVERAGE%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);
