-- =============================================================================
-- V180__derive_distance_pair_from_curriculum.sql — the pair follows the board
-- =============================================================================
-- The cohort's distance pair used to be designated TWICE: once as the
-- BASELINE/DISTANCE milestone tasks on the Curriculum tab, and again as two
-- dropdowns on the Settings tab writing program_settings.baseline_pipeline_id /
-- distance_pipeline_id. The curriculum is now the only designation there is —
-- ProgramAdminService re-derives those two columns from the milestone tasks on
-- every board save. The columns stay: designatedPair / pairsInvolving /
-- memberPairCohort and the cohort header chip all read them.
--
-- This backfills the cohorts that were only ever half-designated — the reason
-- for the change: an admin who added the milestone tasks but never touched the
-- Settings dropdowns had NO pair, so no founder in that cohort was ever
-- compared.
--
-- FILL, NEVER CLEAR. A cohort designated with no milestone task at all still
-- resolves its sides by the earliest/latest-evaluated fallback
-- (ComparisonComputeService.resolveSides), and nulling it here would silently
-- kill live distance reports. Such a cohort converges to the derived value on
-- its next board save, with the Settings card telling the admin which milestone
-- is missing. Existing pairs cannot CONTRADICT their milestone tasks: the
-- validation that made that impossible has been in place since V164.
-- =============================================================================

WITH derived AS (
    SELECT c.id AS cohort_id,
           (SELECT t.ref_id
              FROM program_tasks t
              JOIN program_modules m ON m.id = t.module_id
             WHERE m.cohort_id = c.id
               AND t.task_type = 'ASSESSMENT'
               AND t.milestone_role = 'BASELINE'
               AND t.ref_id IS NOT NULL
             -- Board order, so a cohort carrying two of a role (only possible
             -- from before the one-per-cohort rule) resolves deterministically.
             ORDER BY m.position, t.position
             LIMIT 1) AS baseline_pipeline_id,
           (SELECT t.ref_id
              FROM program_tasks t
              JOIN program_modules m ON m.id = t.module_id
             WHERE m.cohort_id = c.id
               AND t.task_type = 'ASSESSMENT'
               AND t.milestone_role = 'DISTANCE'
               AND t.ref_id IS NOT NULL
             ORDER BY m.position, t.position
             LIMIT 1) AS distance_pipeline_id
      FROM cohorts c
)
INSERT INTO program_settings (cohort_id, baseline_pipeline_id, distance_pipeline_id)
SELECT cohort_id, baseline_pipeline_id, distance_pipeline_id
  FROM derived
 WHERE baseline_pipeline_id IS NOT NULL
    OR distance_pipeline_id IS NOT NULL
-- A cohort with milestone tasks and no settings row at all gets one (every
-- other column has a DEFAULT); one that has a row keeps its pacing untouched.
ON CONFLICT (cohort_id) DO UPDATE
   SET baseline_pipeline_id =
           COALESCE(EXCLUDED.baseline_pipeline_id, program_settings.baseline_pipeline_id),
       distance_pipeline_id =
           COALESCE(EXCLUDED.distance_pipeline_id, program_settings.distance_pipeline_id);
