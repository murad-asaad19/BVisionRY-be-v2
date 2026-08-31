-- V212: DB-level backstop for the destructive-delete guards (2026-08).
--
-- Every guarded delete (course, survey, workshop, exercise template, cohort,
-- session) is an application-level count-then-delete over ON DELETE CASCADE
-- FKs. Under READ COMMITTED there is a race: work committed between the count
-- and the delete is silently cascaded away. Flipping the member-work edges to
-- ON DELETE RESTRICT makes the database itself refuse the parent delete while
-- work rows exist — the app guard stays (it produces the friendly 409 before
-- the click); RESTRICT is the layer that cannot be raced. A lost race now
-- surfaces as the generic 409 "concurrent update, retry" instead of data loss.
--
-- Deliberate destruction paths were switched to EXPLICIT child deletes in the
-- same change (they no longer lean on the cascade):
--   * workshop builder edits / reset      -> delete workshop_task_submissions first
--   * program board save with force:true  -> delete program_submissions of doomed tasks
--   * session delete (own-org roll call)  -> delete session_attendance first
--   * organization hard-delete            -> deletes enrollments / attendance /
--                                            workshop submissions under the org first
--
-- NOT flipped, on purpose:
--   * content_progress / quiz_attempt under sections+lessons — course
--     restructuring is routine authoring and stays cascade (documented in
--     AuthoringService.deleteCourse).
--   * workshop_task_submissions.team_id — team delete is a deliberate
--     same-workshop reshuffle; the task edge already blocks workshop deletes.
--   * user-owned edges (user_id/member_id) — member hard-delete/GDPR remain
--     full cascades of that person's own data.
--   * assignments/submissions under pipelines — already NO ACTION since V4.

-- course delete guard: enrollments (progress + quiz attempts hang off them)
ALTER TABLE enrollment
    DROP CONSTRAINT IF EXISTS fk_enrollment_course;
ALTER TABLE enrollment
    ADD CONSTRAINT fk_enrollment_course
    FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE RESTRICT;

-- survey delete guard: responses (answers cascade off the response)
ALTER TABLE survey_responses
    DROP CONSTRAINT IF EXISTS survey_responses_survey_id_fkey;
ALTER TABLE survey_responses
    ADD CONSTRAINT survey_responses_survey_id_fkey
    FOREIGN KEY (survey_id) REFERENCES surveys (id) ON DELETE RESTRICT;

-- workshop delete guard: submitted task work
ALTER TABLE workshop_task_submissions
    DROP CONSTRAINT IF EXISTS fk_workshop_task_submissions_task;
ALTER TABLE workshop_task_submissions
    ADD CONSTRAINT fk_workshop_task_submissions_task
    FOREIGN KEY (task_id) REFERENCES workshop_exercise_tasks (id) ON DELETE RESTRICT;

-- workshop delete guard: members' pre/post workshop survey responses
-- (respondent_user_id is SET NULL, so these survive even member deletion —
-- the workshop cascade was the one path that silently destroyed them)
ALTER TABLE survey_responses
    DROP CONSTRAINT IF EXISTS fk_survey_responses_workshop;
ALTER TABLE survey_responses
    ADD CONSTRAINT fk_survey_responses_workshop
    FOREIGN KEY (workshop_id) REFERENCES workshops (id) ON DELETE RESTRICT;

-- exercise template delete guard: assignments + anonymous public responses
ALTER TABLE exercise_assignments
    DROP CONSTRAINT IF EXISTS exercise_assignments_template_id_fkey;
ALTER TABLE exercise_assignments
    ADD CONSTRAINT exercise_assignments_template_id_fkey
    FOREIGN KEY (template_id) REFERENCES exercise_templates (id) ON DELETE RESTRICT;

ALTER TABLE public_exercise_responses
    DROP CONSTRAINT IF EXISTS public_exercise_responses_template_id_fkey;
ALTER TABLE public_exercise_responses
    ADD CONSTRAINT public_exercise_responses_template_id_fkey
    FOREIGN KEY (template_id) REFERENCES exercise_templates (id) ON DELETE RESTRICT;

-- cohort delete guard: program submissions (via modules -> tasks)
ALTER TABLE program_submissions
    DROP CONSTRAINT IF EXISTS fk_program_submissions_task;
ALTER TABLE program_submissions
    ADD CONSTRAINT fk_program_submissions_task
    FOREIGN KEY (task_id) REFERENCES program_tasks (id) ON DELETE RESTRICT;

-- cohort delete guard: roll-call history (via sessions)
ALTER TABLE session_attendance
    DROP CONSTRAINT IF EXISTS fk_session_attendance_session;
ALTER TABLE session_attendance
    ADD CONSTRAINT fk_session_attendance_session
    FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE RESTRICT;
