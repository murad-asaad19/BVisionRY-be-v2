-- Make the Personal pillar ("General Information") always present on every pipeline,
-- and guarantee it always carries Name + Gender questions so the AI knows who it's
-- addressing and what pronouns to use.

-- 1. Mark protected (system-managed) questions so they cannot be deleted or retyped.
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS system_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_questions_personal_system_key
    ON questions (pillar_id, system_key)
    WHERE system_key IS NOT NULL;

-- 2. Backfill: for any pipeline that doesn't have a Personal pillar, create one at
--    display_order 0 (and bump the rest down).
DO $$
DECLARE
    p RECORD;
    new_pillar_id UUID;
BEGIN
    FOR p IN
        SELECT pl.id
        FROM pipelines pl
        WHERE NOT EXISTS (
            SELECT 1 FROM pillars pi
            WHERE pi.pipeline_id = pl.id AND pi.type = 'PERSONAL'
        )
    LOOP
        UPDATE pillars
            SET display_order = display_order + 1
            WHERE pipeline_id = p.id;

        INSERT INTO pillars (
            id, pipeline_id, name, description, icon_key, weight, display_order,
            type, ai_rubric_instructions, maturity_thresholds_json, created_at, updated_at
        ) VALUES (
            gen_random_uuid(), p.id, 'General Information',
            'Basic information used to personalise your assessment results.',
            NULL, 0, 0, 'PERSONAL', NULL, '{}'::jsonb, NOW(), NOW()
        )
        RETURNING id INTO new_pillar_id;
    END LOOP;
END $$;

-- 3. Backfill: every Personal pillar must have a Full Name question.
INSERT INTO questions (
    id, pillar_id, type, prompt_text, display_order,
    is_required, weight, config_json, system_key, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    pi.id,
    'FREE_TEXT',
    'Full Name',
    0,
    TRUE,
    1.00,
    NULL,
    'FULL_NAME',
    NOW(),
    NOW()
FROM pillars pi
WHERE pi.type = 'PERSONAL'
  AND NOT EXISTS (
      SELECT 1 FROM questions q
      WHERE q.pillar_id = pi.id AND q.system_key = 'FULL_NAME'
  );

-- 4. Backfill: every Personal pillar must have a Gender question.
INSERT INTO questions (
    id, pillar_id, type, prompt_text, display_order,
    is_required, weight, config_json, system_key, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    pi.id,
    'MULTIPLE_CHOICE',
    'Gender',
    1,
    TRUE,
    1.00,
    '{"options": ["Male", "Female", "Other", "Prefer not to say"], "allowMultiple": false}'::jsonb,
    'GENDER',
    NOW(),
    NOW()
FROM pillars pi
WHERE pi.type = 'PERSONAL'
  AND NOT EXISTS (
      SELECT 1 FROM questions q
      WHERE q.pillar_id = pi.id AND q.system_key = 'GENDER'
  );
