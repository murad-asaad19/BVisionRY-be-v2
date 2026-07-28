-- =============================================================================
-- V158__auto_assign_target_roles.sql — auto-assign targets ROLES, not everyone.
-- =============================================================================
-- Found by manual QA: inviting a COACH into an org immediately created founder
-- assessment assignments for them, with no action by anyone. The cause is that
-- rule applicability was decided by
-- `PipelineAutoAssignmentRepository.findApplicableForMember(orgId, userType)`,
-- which filters on organization and `users.user_type` and NEVER CONSULTS THE
-- ROLE. A rule with `user_type IS NULL` — which the console labels "Applies to
-- all members" — therefore fired for every joiner: coaches, org admins,
-- instructors.
--
-- That is not merely untidy. `web/src/lib/app-nav.ts` states the intent in a
-- comment — "A coach's workspace is their caseload, not the learner surface" —
-- and omits the learner links accordingly, so the coach was handed learner work
-- and simultaneously denied the navigation to find it. Worse, the org
-- Dashboard's Team Score Grid then listed that coach as a measured subject and
-- counted them in "Total Assigned", so a completion rate for a COHORT — the
-- unit of value this product sells — had staff in its denominator.
--
-- WHY A SET AND NOT A SINGLE COLUMN. The operator's decision was "default to
-- MEMBER, but let an admin deliberately opt a role in". A single nullable
-- `target_role` could express "MEMBER" or "anyone" but not "MEMBER and COACH",
-- which is the case an org running a coach-development track actually wants.
-- Forcing that into two rules would collide with the (org, pipeline, user_type)
-- uniqueness the upsert depends on. So roles are a set on the side.
--
-- WHY NOT REUSE user_type. `user_type` is the org's own member taxonomy
-- (LEADER, FOUNDER, …) and is orthogonal: it answers "which KIND of member",
-- while this answers "is this person a measured subject at all". Collapsing
-- them would make it impossible to say "every LEADER who is a MEMBER".
--
-- EXPAND-ONLY: one new table, nothing dropped, renamed or narrowed. The
-- existing `pipeline_auto_assignments` rows are untouched apart from gaining
-- their backfilled role set.

CREATE TABLE pipeline_auto_assignment_roles (
    auto_assignment_id UUID NOT NULL
        REFERENCES pipeline_auto_assignments (id) ON DELETE CASCADE,
    role               VARCHAR(32) NOT NULL,
    PRIMARY KEY (auto_assignment_id, role)
);

-- Read path is always "the roles for this one rule", so the PK's leading column
-- already serves it; no second index.

COMMENT ON TABLE pipeline_auto_assignment_roles IS
    'Roles an auto-assign rule may materialise assignments for. A rule with no '
    'row here matches nobody — see the backfill below for why that cannot arise '
    'for existing rules.';

-- Backfill: every rule that exists today becomes MEMBER-only.
--
-- This is the fix, not merely data movement. Before this migration those rules
-- matched every role; after it they match the founders they were always meant
-- for. It deliberately does NOT delete assignments already materialised for
-- non-members — removing rows a customer can see is the operator's call, not a
-- migration's, and the stale rows are visible in the console for review.
INSERT INTO pipeline_auto_assignment_roles (auto_assignment_id, role)
SELECT id, 'MEMBER' FROM pipeline_auto_assignments;
