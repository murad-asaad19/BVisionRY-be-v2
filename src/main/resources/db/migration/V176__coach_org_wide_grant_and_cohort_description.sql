-- =============================================================================
-- V176__coach_org_wide_grant_and_cohort_description.sql
-- A third coach grain (org-wide) + the cohort's theme line
-- =============================================================================
-- Two changes, both for the dedicated cohort view (redesign spec §13.7):
--
--   1. ORG-WIDE COACH GRANT. V147 gave coach_assignments two grains and pinned
--      them with num_nonnulls(cohort_id, member_id) = 1: a row is a whole-cohort
--      grant XOR a direct founder grant. The org's "house coach" — the one who
--      sits across every cohort — could only be expressed by re-granting them
--      every cohort by hand, and silently lost each cohort created after.
--
--      The third grain is the ABSENCE of both: cohort_id AND member_id null
--      means "every active member of this org" (policy
--      coach_assignment_grain: COHORT_AND_DIRECT_AND_ORG). org_id stays NOT
--      NULL, so the widest grain is still tenant-bounded — there is no
--      cross-org grain and the CHECK cannot express one.
--
--      The CHECK relaxes from = 1 to <= 1 (both-set stays structurally absurd:
--      a row is never two grains at once), and a partial unique index keeps the
--      new grain at one row per (coach, org) — the mirror of V147's two
--      per-grain uniques, so "grant twice" is a constraint violation on all
--      three grains rather than on two.
--
--   2. cohorts.description. The dedicated cohort view shows a theme line under
--      the cohort name when the operator wrote one. Nullable, no backfill, and
--      deliberately NO write endpoint in this change — the read surface lands
--      first, the cohort settings form picks the column up next. No later
--      migration added a description column (verified against V167/V171/V175);
--      this is the first.
-- =============================================================================

-- 1 ── the third grain --------------------------------------------------------

ALTER TABLE coach_assignments DROP CONSTRAINT ck_coach_assignments_grain;
-- at most one grain per row; neither = the whole org
ALTER TABLE coach_assignments ADD CONSTRAINT ck_coach_assignments_grain
    CHECK (num_nonnulls(cohort_id, member_id) <= 1);

-- One org-wide grant per coach per org (V147's uq_coach_assignments_cohort /
-- _member cover the other two grains; neither matches a both-null row).
CREATE UNIQUE INDEX uq_coach_assignments_org_wide
    ON coach_assignments (coach_id, org_id)
    WHERE cohort_id IS NULL AND member_id IS NULL;

-- 2 ── the cohort's theme line ------------------------------------------------

ALTER TABLE cohorts ADD COLUMN description text;
