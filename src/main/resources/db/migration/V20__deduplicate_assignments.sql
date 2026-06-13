-- V20: Remove duplicate assignments, keeping the best one per (pipeline_id, organization_id)

-- Step 1: Identify assignments to KEEP (best per pipeline+org: most evaluated submissions, then earliest)
CREATE TEMP TABLE keep_assignments AS
SELECT DISTINCT ON (pipeline_id, organization_id) id
FROM assignments
ORDER BY pipeline_id, organization_id,
    (SELECT COUNT(*) FROM submissions sub WHERE sub.assignment_id = assignments.id AND sub.status = 'EVALUATED') DESC,
    created_at ASC;

-- Step 2: Delete child data for duplicate assignments
DELETE FROM answers WHERE submission_id IN (
    SELECT s.id FROM submissions s WHERE s.assignment_id NOT IN (SELECT id FROM keep_assignments)
);

DELETE FROM pillar_evaluations WHERE submission_id IN (
    SELECT s.id FROM submissions s WHERE s.assignment_id NOT IN (SELECT id FROM keep_assignments)
);

DELETE FROM overall_summaries WHERE submission_id IN (
    SELECT s.id FROM submissions s WHERE s.assignment_id NOT IN (SELECT id FROM keep_assignments)
);

DELETE FROM submissions WHERE assignment_id NOT IN (SELECT id FROM keep_assignments);

DELETE FROM assignments WHERE id NOT IN (SELECT id FROM keep_assignments);

DROP TABLE keep_assignments;

-- Step 3: Add unique constraint to prevent future duplicates
ALTER TABLE assignments ADD CONSTRAINT uq_assignment_pipeline_org UNIQUE (pipeline_id, organization_id);
