-- V146: btree indexes on the user-referencing columns the GDPR export and
-- erasure flows scan. Every one of these is an FK child of users(id) with no
-- leading-column index today: Postgres must also scan them to enforce the
-- ON DELETE CASCADE / SET NULL actions, so a single account deletion was a
-- sequential scan per table. Additive only — CREATE INDEX, nothing else.
--
-- Composite keys already covering these columns as a NON-leading column
-- (program_module_members(module_id, user_id), workshop_team_members(workshop_id, user_id),
-- insight_report_member_ids(insight_report_id, member_id), review(course_id, user_id))
-- cannot serve a bare `user_id = ?` lookup, hence the single-column indexes below.

CREATE INDEX IF NOT EXISTS idx_program_module_members_user ON program_module_members (user_id);
CREATE INDEX IF NOT EXISTS idx_workshop_team_members_user ON workshop_team_members (user_id);
CREATE INDEX IF NOT EXISTS idx_insight_report_member_ids_member ON insight_report_member_ids (member_id);
CREATE INDEX IF NOT EXISTS idx_exercise_assignments_user ON exercise_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_exercise_comments_author ON exercise_comments (author_id);
CREATE INDEX IF NOT EXISTS idx_review_user ON review (user_id);
CREATE INDEX IF NOT EXISTS idx_survey_responses_respondent_user ON survey_responses (respondent_user_id);
CREATE INDEX IF NOT EXISTS idx_assignments_assigned_by ON assignments (assigned_by);
CREATE INDEX IF NOT EXISTS idx_invitations_invited_by ON invitations (invited_by);
CREATE INDEX IF NOT EXISTS idx_users_invited_by ON users (invited_by);
CREATE INDEX IF NOT EXISTS idx_submission_pillar_unlocks_admin ON submission_pillar_unlocks (unlocked_by_admin_id);
CREATE INDEX IF NOT EXISTS idx_pillar_evaluation_history_admin ON pillar_evaluation_history (archived_by_admin_id);
CREATE INDEX IF NOT EXISTS idx_overall_summary_history_admin ON overall_summary_history (archived_by_admin_id);
CREATE INDEX IF NOT EXISTS idx_course_instructor ON course (instructor_id);

-- Erasure matches invitations by lower(email) — there is no FK to the invited
-- user, so this expression index is what keeps that step off a seq scan.
CREATE INDEX IF NOT EXISTS idx_invitations_lower_email ON invitations (lower(email));
