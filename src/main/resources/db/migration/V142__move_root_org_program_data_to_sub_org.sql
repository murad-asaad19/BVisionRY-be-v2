-- The Program Flow and Workshops consoles now list sub-organizations only, so
-- any cohorts / program teams / modules / workshops still attached to a ROOT
-- organization would be unreachable. V136 moved every org's data into a
-- 'General' child, but orgs created since then could still take program data
-- directly on the root (the old switcher listed root orgs too) — move that
-- down, mirroring V136.
--
-- Target child per affected root: its 'General' child, else its first child,
-- else a freshly created 'General' (same shape as V136 — FREE tier, active
-- mirrors the parent). Nothing is stranded and no root is left holding
-- program data.

CREATE TEMPORARY TABLE tmp_program_subs AS
SELECT r.id AS root_id,
       r.is_active,
       COALESCE(
           (SELECT s.id FROM organizations s
             WHERE s.parent_organization_id = r.id
             ORDER BY (s.name <> 'General'), s.id
             LIMIT 1),
           gen_random_uuid()) AS sub_id,
       NOT EXISTS (SELECT 1 FROM organizations s
                    WHERE s.parent_organization_id = r.id) AS must_create
FROM organizations r
WHERE r.parent_organization_id IS NULL
  AND (EXISTS (SELECT 1 FROM cohorts c WHERE c.org_id = r.id)
    OR EXISTS (SELECT 1 FROM workshops w WHERE w.org_id = r.id)
    OR EXISTS (SELECT 1 FROM teams t WHERE t.org_id = r.id)
    OR EXISTS (SELECT 1 FROM program_modules m WHERE m.org_id = r.id));

INSERT INTO organizations (id, name, description, subscription_tier, is_active, parent_organization_id)
SELECT sub_id, 'General', 'Default sub-organization', 'FREE', is_active, root_id
FROM tmp_program_subs
WHERE must_create;

UPDATE cohorts c
SET org_id = m.sub_id
FROM tmp_program_subs m
WHERE c.org_id = m.root_id;

-- uq_teams_org_name is UNIQUE (org_id, name): a root team whose name already
-- exists on the child is suffixed rather than aborting the migration.
UPDATE teams t
SET org_id = m.sub_id,
    name = CASE WHEN EXISTS (SELECT 1 FROM teams x
                              WHERE x.org_id = m.sub_id AND x.name = t.name)
                THEN t.name || ' (moved)' ELSE t.name END
FROM tmp_program_subs m
WHERE t.org_id = m.root_id;

UPDATE program_modules pm
SET org_id = m.sub_id
FROM tmp_program_subs m
WHERE pm.org_id = m.root_id;

UPDATE workshops w
SET org_id = m.sub_id
FROM tmp_program_subs m
WHERE w.org_id = m.root_id;

-- Workshop join links follow their workshop (workshop_id is untouched, so the
-- partial unique index on it can't collide). Org-wide links (workshop_id NULL)
-- belong to the org itself and stay put — moving them could collide with the
-- child's own active org-wide link.
UPDATE join_links j
SET organization_id = m.sub_id
FROM tmp_program_subs m
WHERE j.organization_id = m.root_id
  AND j.workshop_id IS NOT NULL;

DROP TABLE tmp_program_subs;
