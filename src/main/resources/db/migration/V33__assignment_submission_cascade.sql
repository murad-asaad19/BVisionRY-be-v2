-- Cancelling an assignment (DELETE /api/organizations/:orgId/assignments/:id)
-- needs to also remove the attached submission + its answers / evaluations.
-- submissions.submission_id already cascades its children (answers, pillar_evaluations,
-- overall_summaries) but submissions.assignment_id was NOT cascading from assignments,
-- so AssignmentService.cancelAssignment threw a FK violation for any IN_PROGRESS row.
ALTER TABLE submissions
    DROP CONSTRAINT IF EXISTS submissions_assignment_id_fkey;

ALTER TABLE submissions
    ADD CONSTRAINT submissions_assignment_id_fkey
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE;
