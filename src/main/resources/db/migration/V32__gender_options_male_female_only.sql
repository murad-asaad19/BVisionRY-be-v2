-- Narrow the Gender question's options to Male/Female only.
-- Previously seeded with Male/Female/Other/Prefer not to say; product decision to
-- keep the set binary so pronouns map unambiguously for the AI.
UPDATE questions
SET config_json = jsonb_build_object(
        'options', jsonb_build_array('Male', 'Female'),
        'allowMultiple', false
    )
WHERE system_key = 'GENDER';
