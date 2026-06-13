-- Recovery migration for submissions affected by two pre-fix bugs:
--
--  1. The retry-evaluation flow dispatched its @Async worker before the
--     FAILED→SUBMITTED transaction committed, so the worker reloaded the row,
--     saw FAILED, and bailed — leaving the submission stuck in SUBMITTED with
--     no overall_summary and no way back to the Retry button. Known affected:
--     eng.asm.89@gmail.com. Flip those rows back to FAILED so they can be
--     retried through the UI now that the AfterCommit fix is in place.
--
--  2. Before the transactional fix landed, evaluateSubmission re-inserted
--     pillar_evaluations on retry without clearing prior rows. Submissions
--     that ultimately succeeded on a second run now carry duplicate pillar
--     rows (one set per attempt). Known affected: wafash.alhayek@gmail.com.
--     Keep only the latest evaluated_at row per (submission_id, pillar_id) —
--     pillar_evaluations has no unique key on that pair, so dedupe must be
--     done explicitly. The dedupe is global because the same race could have
--     touched other submissions we haven't identified.

-- 1. Re-mark stuck submissions for eng.asm.89@gmail.com as FAILED.
UPDATE submissions
SET status = 'FAILED',
    failure_reason = 'Retry lost to async race — please retry'
WHERE status = 'SUBMITTED'
  AND evaluated_at IS NULL
  AND id NOT IN (SELECT submission_id FROM overall_summaries)
  AND assignment_id IN (
      SELECT a.id
      FROM assignments a
      JOIN users u ON u.id = a.user_id
      WHERE u.email = 'eng.asm.89@gmail.com'
  );

-- Drop any orphaned pillar_evaluations for submissions we just flipped back
-- to FAILED, so the upcoming retry doesn't append a third set on top.
DELETE FROM pillar_evaluations
WHERE submission_id IN (
    SELECT s.id
    FROM submissions s
    JOIN assignments a ON a.id = s.assignment_id
    JOIN users u ON u.id = a.user_id
    WHERE u.email = 'eng.asm.89@gmail.com'
      AND s.status = 'FAILED'
      AND s.evaluated_at IS NULL
);

-- 2. Deduplicate pillar_evaluations globally: for each (submission_id, pillar_id)
--    pair with multiple rows, keep the most recent by evaluated_at.
DELETE FROM pillar_evaluations
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY submission_id, pillar_id
                   ORDER BY evaluated_at DESC, created_at DESC
               ) AS rn
        FROM pillar_evaluations
    ) ranked
    WHERE rn > 1
);
