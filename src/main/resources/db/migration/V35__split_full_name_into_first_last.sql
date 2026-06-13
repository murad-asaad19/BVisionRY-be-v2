-- Replace the single "Full Name" field in every Personal pillar with two
-- separate locked questions: "First Name" and "Last Name".
--
-- The product isn't live yet so we don't bother splitting existing answers —
-- the FULL_NAME question + its answers are deleted outright and members will
-- re-enter their name against the new First/Last fields on next login.

-- 1. Delete any answers tied to the old FULL_NAME question.
DELETE FROM answers
WHERE question_id IN (
    SELECT id FROM questions WHERE system_key = 'FULL_NAME'
);

-- 2. Delete the FULL_NAME question itself from every Personal pillar.
DELETE FROM questions WHERE system_key = 'FULL_NAME';

-- 3. Insert the two new locked questions (First Name + Last Name) for every
--    Personal pillar that doesn't already have them. They live at display_order
--    0 and 1; the Gender question that used to sit at 1 gets bumped to 2 in
--    step 4 below. Last Name carries the sameRow layout hint so it renders
--    side-by-side with First Name.
INSERT INTO questions (
    id, pillar_id, type, prompt_text, display_order,
    is_required, weight, config_json, system_key, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    pi.id,
    'FREE_TEXT',
    'First Name',
    0,
    TRUE,
    1.00,
    NULL,
    'FIRST_NAME',
    NOW(),
    NOW()
FROM pillars pi
WHERE pi.type = 'PERSONAL'
  AND NOT EXISTS (
      SELECT 1 FROM questions q
      WHERE q.pillar_id = pi.id AND q.system_key = 'FIRST_NAME'
  );

INSERT INTO questions (
    id, pillar_id, type, prompt_text, display_order,
    is_required, weight, config_json, system_key, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    pi.id,
    'FREE_TEXT',
    'Last Name',
    1,
    TRUE,
    1.00,
    '{"layout": {"sameRow": true}}'::jsonb,
    'LAST_NAME',
    NOW(),
    NOW()
FROM pillars pi
WHERE pi.type = 'PERSONAL'
  AND NOT EXISTS (
      SELECT 1 FROM questions q
      WHERE q.pillar_id = pi.id AND q.system_key = 'LAST_NAME'
  );

-- 4. Bump the existing Gender question (and any admin-added questions) down
--    so the pillar reads: First Name (0), Last Name (1), Gender (2), ...
UPDATE questions q
SET display_order = q.display_order + 1
FROM pillars pi
WHERE q.pillar_id = pi.id
  AND pi.type = 'PERSONAL'
  AND q.system_key IS DISTINCT FROM 'FIRST_NAME'
  AND q.system_key IS DISTINCT FROM 'LAST_NAME'
  AND q.display_order >= 1;
