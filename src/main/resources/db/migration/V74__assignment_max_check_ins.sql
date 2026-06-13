-- Per-assignment cap on how many times the assigned member can complete the
-- pipeline. Each completion is a "check-in" — a periodic measurement of where
-- the member stands. Default 1 preserves the historical single-completion
-- behavior. The CHECK keeps callers from setting nonsensical values (zero or
-- negative). When a member has fewer than {@code max_check_ins} submissions
-- for the assignment and the latest one is EVALUATED, they may start a fresh
-- check-in.
ALTER TABLE assignments
    ADD COLUMN max_check_ins INT NOT NULL DEFAULT 1
        CHECK (max_check_ins >= 1);

-- Mirror the cap on the auto-assign rule so future joiners materialised from
-- the rule inherit the admin's intended check-in count rather than always
-- collapsing to the default.
ALTER TABLE pipeline_auto_assignments
    ADD COLUMN max_check_ins INT NOT NULL DEFAULT 1
        CHECK (max_check_ins >= 1);
