-- One pillar_evaluation row per (submission, pillar).
--
-- The evaluation upsert (EvaluationService.savePillarEvaluations) re-uses the
-- existing row per pillar so a re-run updates in place. Without a unique
-- constraint, two rows for the same (submission_id, pillar_id) could accumulate
-- (e.g. a concurrent re-evaluation before the claim guard existed); the merge
-- then keeps one row to update and silently strands the duplicate(s), so the
-- results report aggregates a stale score alongside the fresh one.
--
-- Step 1: collapse any existing duplicates, keeping the most recently evaluated
-- row per (submission_id, pillar_id) (tie-break on id for determinism).
DELETE FROM pillar_evaluations a
USING pillar_evaluations b
WHERE a.submission_id = b.submission_id
  AND a.pillar_id = b.pillar_id
  AND (
        a.evaluated_at < b.evaluated_at
        OR (a.evaluated_at = b.evaluated_at AND a.id < b.id)
      );

-- Step 2: enforce the invariant so the upsert can never strand a duplicate again.
ALTER TABLE pillar_evaluations
    ADD CONSTRAINT uq_pillar_evaluations_submission_pillar UNIQUE (submission_id, pillar_id);
