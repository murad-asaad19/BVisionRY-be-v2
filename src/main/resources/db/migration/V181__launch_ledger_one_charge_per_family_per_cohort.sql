-- =============================================================================
-- V181__launch_ledger_one_charge_per_family_per_cohort.sql
-- (redesign spec §8 + §13.4 — quota hardening)
-- =============================================================================
-- The launch ledger metered PER ASSIGNMENT, but the unit that pays is the
-- BILLING FAMILY: a cohort assigned to two sub-orgs of one root charged that
-- root twice per launch, and a double-clicked Launch could insert two rows for
-- the same cohort (burning a granted extra launch and firing enrollment
-- notifications twice). The invariant is "one charge per billing family per
-- cohort, ever" — enforce it in the database, where every code path present
-- and future has to pass through it.
--
-- Grants are untouched: they live in cohort_launch_grants, a separate table
-- (V167), so the ledger holds ONLY consume rows and a plain UNIQUE suffices —
-- no partial index needed.
-- =============================================================================

-- Collapse any double charges the bug already wrote (possible on unlimited
-- tiers, where the second consume did not 409). Keep the EARLIEST row — it is
-- the honest launch instant; the later ones were the same launch counted again.
DELETE FROM cohort_launch_ledger l
USING cohort_launch_ledger keeper
WHERE keeper.org_id = l.org_id
  AND keeper.cohort_id = l.cohort_id
  AND (keeper.launched_at, keeper.id) < (l.launched_at, l.id);

-- org_id is the billing root (V167), so this IS "one charge per family per
-- cohort". The append-only / never-refund rule stands: rows still outlive the
-- cohort, there is still no FK on cohort_id.
ALTER TABLE cohort_launch_ledger
    ADD CONSTRAINT uq_launch_ledger_org_cohort UNIQUE (org_id, cohort_id);
