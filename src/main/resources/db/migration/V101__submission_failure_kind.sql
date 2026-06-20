-- Classifies WHY a submission failed so the respondent retake flow can branch:
--   SYSTEM = AI/infra failure, answers are valid -> just re-run evaluation.
--   INPUT  = answers need changing -> unlock editing (forward-looking; no producer yet).
-- Nullable: historical failures are implicitly SYSTEM (the retake treats NULL as SYSTEM),
-- so no backfill is required.
ALTER TABLE submissions ADD COLUMN failure_kind VARCHAR(16);

ALTER TABLE submissions ADD CONSTRAINT submissions_failure_kind_check
    CHECK (failure_kind IS NULL OR failure_kind IN ('SYSTEM', 'INPUT'));
