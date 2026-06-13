-- Dedicated evaluation model for public (QR-link) assessments.
-- NULL = inherit default_evaluation_model.
ALTER TABLE ai_configurations
    ADD COLUMN public_assessment_model VARCHAR(255);
