-- Add core pattern and moving forward narrative to overall summaries
ALTER TABLE overall_summaries ADD COLUMN core_pattern VARCHAR(500);
ALTER TABLE overall_summaries ADD COLUMN moving_forward_narrative TEXT;

-- Add self-assessment gap to pillar evaluations
ALTER TABLE pillar_evaluations ADD COLUMN self_assessment_gap INTEGER;
