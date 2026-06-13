-- One answer per (submission, question).
--
-- The answer upsert (AssessmentService.saveAnswerInternal) was a non-atomic
-- check-then-act: it SELECTed the existing answer and INSERTed when absent.
-- Without a unique constraint, two concurrent autosaves for the same field
-- both saw "no row" and both INSERTed, producing duplicate answers. The read
-- path then crashed collecting answers into a Map keyed by question id.
--
-- Step 1: collapse existing duplicates, keeping the most recently updated row
-- per (submission_id, question_id) (tie-break on id for determinism).
DELETE FROM answers a
USING answers b
WHERE a.submission_id = b.submission_id
  AND a.question_id = b.question_id
  AND (
        a.updated_at < b.updated_at
        OR (a.updated_at = b.updated_at AND a.id < b.id)
      );

-- Step 2: enforce the invariant so the race can never silently corrupt again.
ALTER TABLE answers
    ADD CONSTRAINT uq_answers_submission_question UNIQUE (submission_id, question_id);
