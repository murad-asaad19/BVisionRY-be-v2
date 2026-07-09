-- Default sub-organizations: members no longer live directly in a root org.
-- Every root organization gets a 'General' sub-organization, and all
-- member-facing rows (MEMBER/MANAGER users, their invitations, assignments,
-- reports, join links, workshops, cohorts, teams, program modules) move into
-- it. Root orgs keep only ORG_ADMIN / INSTRUCTOR users and the parent-scoped
-- catalog (course/content/section/tag/review), audit_logs, upgrade_requests.
--
-- A temp mapping table (parent -> new child id) lets every UPDATE below join
-- against the same generated ids in one migration transaction.

CREATE TEMPORARY TABLE tmp_default_subs AS
SELECT gen_random_uuid() AS sub_id, o.id AS parent_id, o.is_active
FROM organizations o
WHERE o.parent_organization_id IS NULL;

-- a) The 'General' child per root org. Own tier row is FREE (sub-orgs inherit
--    the parent's effective tier); active mirrors the parent.
INSERT INTO organizations (id, name, description, subscription_tier, is_active, parent_organization_id)
SELECT sub_id, 'General', 'Default sub-organization', 'FREE', is_active, parent_id
FROM tmp_default_subs;

-- b) Re-parent member-facing rows currently pointing at the root org.

UPDATE users u
SET organization_id = m.sub_id
FROM tmp_default_subs m
WHERE u.organization_id = m.parent_id
  AND u.role IN ('MEMBER', 'MANAGER');

UPDATE invitations i
SET organization_id = m.sub_id
FROM tmp_default_subs m
WHERE i.organization_id = m.parent_id
  AND i.role IN ('MEMBER', 'MANAGER');

UPDATE assignments a
SET organization_id = m.sub_id
FROM tmp_default_subs m
WHERE a.organization_id = m.parent_id;

UPDATE insight_reports r
SET organization_id = m.sub_id
FROM tmp_default_subs m
WHERE r.organization_id = m.parent_id;

UPDATE pipeline_auto_assignments p
SET organization_id = m.sub_id
FROM tmp_default_subs m
WHERE p.organization_id = m.parent_id;

-- Safe w.r.t. the partial unique indexes (one active org-wide link per org,
-- one active link per workshop): each fresh child starts with zero links and
-- each parent maps to exactly one child, so a parent's at-most-one active
-- org-wide link lands alone on its child; workshop_id is untouched.
UPDATE join_links j
SET organization_id = m.sub_id
FROM tmp_default_subs m
WHERE j.organization_id = m.parent_id;

UPDATE workshops w
SET org_id = m.sub_id
FROM tmp_default_subs m
WHERE w.org_id = m.parent_id;

UPDATE cohorts c
SET org_id = m.sub_id
FROM tmp_default_subs m
WHERE c.org_id = m.parent_id;

UPDATE teams t
SET org_id = m.sub_id
FROM tmp_default_subs m
WHERE t.org_id = m.parent_id;

UPDATE program_modules pm
SET org_id = m.sub_id
FROM tmp_default_subs m
WHERE pm.org_id = m.parent_id;

DROP TABLE tmp_default_subs;
