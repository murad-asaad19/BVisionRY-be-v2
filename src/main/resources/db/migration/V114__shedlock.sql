-- ShedLock coordination table for distributed @Scheduled locking.
--
-- The five reaper/expiry jobs (TrialExpiryJob, EvaluationReaper,
-- PublicSubmissionReaper, AICallLogRetentionJob) run under plain
-- @EnableScheduling, so every replica fires every job on every tick. Once the
-- Railway service scales past one instance that means duplicate trial-expiry
-- processing and duplicate reap DELETEs racing each other. ShedLock serialises
-- each job to a single instance per tick by taking a row lock in this table
-- (see SchedulerLockConfig + the @SchedulerLock annotations on each job).
--
-- This is the standard ShedLock schema for the JdbcTemplate provider. Columns
-- and the VARCHAR(64) name primary key are fixed by the library. We use
-- TIMESTAMPTZ (the codebase convention, e.g. V50/V109) rather than the docs'
-- bare TIMESTAMP so the lock timestamps are unambiguous under DB time; the
-- provider is configured with usingDbTime(), so ShedLock reads and compares
-- lock_until against the database clock, immune to per-instance clock skew.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
