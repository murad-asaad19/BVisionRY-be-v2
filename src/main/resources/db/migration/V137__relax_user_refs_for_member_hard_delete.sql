-- V137: Permanently deleting a single member (hard delete) must not be blocked
-- by rows they authored. Mirrors V34 (which relaxed pipelines/invited_by/audit
-- refs for org hard-delete): authored/administrative references survive the
-- author's deletion with SET NULL, while the member's own personal data
-- (submissions) is erased with them via CASCADE.

-- 1. Authored / administrative references: keep the row, drop the attribution.

ALTER TABLE assignments
    ALTER COLUMN assigned_by DROP NOT NULL;
ALTER TABLE assignments
    DROP CONSTRAINT IF EXISTS assignments_assigned_by_fkey;
ALTER TABLE assignments
    ADD CONSTRAINT assignments_assigned_by_fkey
    FOREIGN KEY (assigned_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE invitations
    ALTER COLUMN invited_by DROP NOT NULL;
ALTER TABLE invitations
    DROP CONSTRAINT IF EXISTS invitations_invited_by_fkey;
ALTER TABLE invitations
    ADD CONSTRAINT invitations_invited_by_fkey
    FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE join_links
    ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE join_links
    DROP CONSTRAINT IF EXISTS join_links_created_by_fkey;
ALTER TABLE join_links
    ADD CONSTRAINT join_links_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE surveys
    ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE surveys
    DROP CONSTRAINT IF EXISTS surveys_created_by_fkey;
ALTER TABLE surveys
    ADD CONSTRAINT surveys_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE public_assessment_links
    ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE public_assessment_links
    DROP CONSTRAINT IF EXISTS public_assessment_links_created_by_fkey;
ALTER TABLE public_assessment_links
    ADD CONSTRAINT public_assessment_links_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE pipeline_auto_assignments
    ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE pipeline_auto_assignments
    DROP CONSTRAINT IF EXISTS pipeline_auto_assignments_created_by_fkey;
ALTER TABLE pipeline_auto_assignments
    ADD CONSTRAINT pipeline_auto_assignments_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE pipeline_auto_assignments
    DROP CONSTRAINT IF EXISTS pipeline_auto_assignments_updated_by_fkey;
ALTER TABLE pipeline_auto_assignments
    ADD CONSTRAINT pipeline_auto_assignments_updated_by_fkey
    FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE ai_configurations
    DROP CONSTRAINT IF EXISTS ai_configurations_updated_by_fkey;
ALTER TABLE ai_configurations
    ADD CONSTRAINT ai_configurations_updated_by_fkey
    FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE submission_pillar_unlocks
    ALTER COLUMN unlocked_by_admin_id DROP NOT NULL;
ALTER TABLE submission_pillar_unlocks
    DROP CONSTRAINT IF EXISTS submission_pillar_unlocks_unlocked_by_admin_id_fkey;
ALTER TABLE submission_pillar_unlocks
    ADD CONSTRAINT submission_pillar_unlocks_unlocked_by_admin_id_fkey
    FOREIGN KEY (unlocked_by_admin_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE pillar_evaluation_history
    DROP CONSTRAINT IF EXISTS pillar_evaluation_history_archived_by_admin_id_fkey;
ALTER TABLE pillar_evaluation_history
    ADD CONSTRAINT pillar_evaluation_history_archived_by_admin_id_fkey
    FOREIGN KEY (archived_by_admin_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE overall_summary_history
    DROP CONSTRAINT IF EXISTS overall_summary_history_archived_by_admin_id_fkey;
ALTER TABLE overall_summary_history
    ADD CONSTRAINT overall_summary_history_archived_by_admin_id_fkey
    FOREIGN KEY (archived_by_admin_id) REFERENCES users(id) ON DELETE SET NULL;

-- 2. The member's own data: erased with them. Submissions already cascade from
-- assignments (whose user_id FK is CASCADE); this direct FK must agree, or the
-- statement-end NO ACTION check would block deleting a user with any
-- submission not reached through their own assignments.

ALTER TABLE submissions
    DROP CONSTRAINT IF EXISTS submissions_user_id_fkey;
ALTER TABLE submissions
    ADD CONSTRAINT submissions_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
