-- Claim marker that stops the same submission from being AI-evaluated twice.
-- A worker atomically stamps this column while the row is still SUBMITTED; a
-- competing worker (e.g. from a double-submit dispatching two evaluations) sees
-- a fresh claim and skips, so the expensive AI evaluation never runs twice and
-- the loser can't overwrite the winner's result. Nullable and reset to NULL
-- whenever the submission is re-queued for evaluation (retry / admin re-eval);
-- a staleness window lets a claim left behind by a crashed worker be reclaimed.
ALTER TABLE submissions
    ADD COLUMN evaluation_claimed_at TIMESTAMP WITH TIME ZONE;
