-- =============================================================================
-- e2e-seed.sql — the authored fixture the Playwright suite runs against
-- =============================================================================
--
-- WHEN THIS APPLIES
-- -----------------
--   1. An EMPTY Postgres database.
--   2. The backend has booted once, so Flyway has run V1..V160 to completion.
--      (Flyway is schema-owned: `spring.flyway.enabled=true`, `ddl-auto=none`.)
--   3. THEN this file, exactly once:
--        psql -U bvisionry -d bvisionry -v ON_ERROR_STOP=1 -f e2e-seed.sql
--
--   It is NOT a migration and must never be moved under `db/migration`.
--   It is NOT idempotent, deliberately: every statement is a plain INSERT (or a
--   single UPDATE keyed on an email), so a second apply ERRORS on a unique
--   violation instead of silently doubling the fixture. A re-seed means a fresh
--   database, which is the only state this file claims to be correct against.
--
-- WHY IT IS AUTHORED AND NOT DUMPED
-- ---------------------------------
--   Sandbox lanes are fed from a pg_dump of the operator's live dev database:
--   host-local, unversioned, and full of real personal data. That dump can
--   never be committed, which is the single reason `web/.github/workflows/
--   e2e.yml` is `workflow_dispatch` and not `pull_request`. Every row below is
--   invented. There is no real person, address, company or phone number in this
--   file, and no fragment of any dump.
--
-- WHAT IT GUARANTEES
-- ------------------
--   * The five identities `web/e2e/_helpers.ts` ROLES signs in, all ACTIVE, all
--     on the documented local password `Password123!`, plus three extra members
--     the specs address BY NAME (see the roster note below).
--   * The org family the suite pins: root "Test Organization" on an
--     unlimited-cohort tier, sub-org "General" on the UUID `_helpers.SUB_ORG_ID`
--     hardcodes, and a second sub-org carrying the ROI movement fixture on the
--     UUIDs `roi-report.spec.ts` MOVEMENT hardcodes.
--   * One PUBLISHED pipeline on the UUID `roi-report.spec.ts` pins, with two
--     banded STANDARD pillars and one PERSONAL pillar.
--   * Assessments: one founder measured once (General), one measured once
--     (General), one measured TWICE (Alumni) — the only real intake-to-latest
--     movement in the fixture. Total measured founders is 3, comfortably under
--     the benchmark min-sample of 30, which is what `benchmarking.spec.ts`
--     asserts the "Insufficient data." branch on.
--   * A program-flow cohort with LIVE tasks so the member dashboard renders
--     "1 of 2 tasks".
--   * Two exercise templates and the member assignments + submissions
--     `release-flows.spec.ts` and `coach-console.spec.ts` name.
--   * A survey with two individual responses.
--
-- WHAT IS DELIBERATELY ABSENT, AND WHY THAT IS SAFE
-- -------------------------------------------------
--   * COURSES / CATALOG — not seeded. Migration V77 already ships the nine
--     published "Bvisionry Academy" courses, including all three slugs the
--     COURSES-ON branch of `catalog.spec.ts` walks
--     (leadership-essentials-new-managers with exactly Video/Pdf/Page/Quiz/
--     Certification, onboarding-welcome-to-the-team with a Link lesson,
--     advanced-excel-for-analysts with a Scorm lesson) and the `Leadership`
--     category facet. Re-seeding them here would collide on `uq_course_slug`.
--   * THE COACH — `coach.e2e@example.com`, "Coach E2E Cohort", "Coach E2E
--     Other" and the cohort grant are minted by `auth.setup.ts` through real
--     invite/accept/grant flows. Seeding them would make that setup a no-op and
--     stop exercising those flows.
--   * MINIO OBJECTS — no `minio://` marker is written anywhere. Every surface
--     below renders from relational rows alone, so the suite does not depend on
--     object storage holding anything. `organizations.brand_logo_marker` stays
--     NULL; `white-label.spec.ts` reads the current branding, uploads its own
--     1x1 PNG and restores what it found, so NULL is a valid starting state.
--   * ANNOUNCEMENTS, WORKSHOPS, JOIN LINKS, INVITATIONS, AI CONFIG — the specs
--     that touch these create their own rows (announcements, join links) or
--     only assert the route loads. `ai_configurations` already holds an
--     OPENROUTER row from migration V6, which is what `ux-p0.spec.ts` reads.
--   * `admin@bvisionry.com` is not INSERTed. Migration V16 creates it and V113
--     then deactivates it and revokes its hash; `SuperAdminBootstrap` is
--     `existsByEmail`-guarded so it skips rather than repairs. The one UPDATE in
--     this file is that repair.
--
-- UUIDs are fixed literals throughout — nothing is generated. The `e2e5eed0-`
-- prefix marks a row this file owns; the handful of other literals are the ones
-- the web repo hardcodes and are annotated where they appear.
-- Timestamps are relative intervals so the fixture does not age into the past.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 0. The super admin. V16 created it, V113 revoked it. Repair, do not insert:
--    `users.email` is unique and SuperAdminBootstrap will not touch an existing
--    row. The hash below is bcrypt cost 10 of `Password123!`, produced by the
--    application's own BCryptPasswordEncoder (SecurityConfig, no-arg ⇒ 10) and
--    verified by a real POST /api/auth/login.
-- -----------------------------------------------------------------------------
UPDATE users
   SET password_hash = '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC',
       status        = 'ACTIVE',
       name          = 'Super Admin',
       user_type     = 'LEADER',
       activated_at  = now(),
       updated_at    = now()
 WHERE email = 'admin@bvisionry.com';

-- -----------------------------------------------------------------------------
-- 1. Organizations
--    The tier lives on the ROOT: `changeTier` refuses a sub-organization
--    outright, and the cohort meter spans the billing FAMILY. FOUNDER_SUCCESS is
--    unlimited-cohort, which is the precondition auth.setup.ts provisions before
--    creating its two coach cohorts — seeding it here means that PATCH answers
--    400 "already on the ... tier", which auth.setup accepts.
--    PREMIUM is still legal in the DB CHECK but was deleted from the Java enum;
--    do not use it.
-- -----------------------------------------------------------------------------
INSERT INTO organizations (id, name, description, subscription_tier, is_active, parent_organization_id) VALUES
  ('e2e5eed0-0000-4000-8000-000000000001', 'Test Organization',
   'Root fixture organization for the end-to-end suite.', 'FOUNDER_SUCCESS', true, NULL),
  -- pinned by web/e2e/_helpers.ts SUB_ORG_ID
  ('2a93054a-f352-4220-a321-a84063924096', 'General',
   'Default sub-organization — the org-admin fixture''s own console.', 'FOUNDER_SUCCESS', true,
   'e2e5eed0-0000-4000-8000-000000000001'),
  -- pinned by web/e2e/roi-report.spec.ts MOVEMENT.orgId
  ('e0077f30-1008-4362-9969-a759e8dfd5e8', 'Alumni Programme',
   'Second sub-organization — holds the only twice-measured founder.', 'FOUNDER_SUCCESS', true,
   'e2e5eed0-0000-4000-8000-000000000001');

-- Console membership is an explicit row since V143; without it an org does not
-- appear in the Program Flow console even though it has cohorts.
INSERT INTO program_surface_orgs (org_id, surface) VALUES
  ('2a93054a-f352-4220-a321-a84063924096', 'PROGRAM_FLOW'),
  ('e0077f30-1008-4362-9969-a759e8dfd5e8', 'PROGRAM_FLOW');

-- -----------------------------------------------------------------------------
-- 2. The roster
--    Every account shares the local password `Password123!` and the same hash as
--    the super admin above. status must be ACTIVE: AuthService.login refuses any
--    other value, and the coach console's visible-founder rule and the cohort
--    member counts both filter on role='MEMBER' AND status='ACTIVE'.
--    `user_type` has had no DB default since V47 — set it explicitly or the
--    column lands NULL where the entity would have said 'LEADER'.
--
--    NAMES ARE LOAD-BEARING. coach-console.spec.ts asserts the exact display
--    names "Exercise Tester", "Invite Flow Tester", "Loop Test Member" and
--    "Join Flow Tester"; auth.spec.ts asserts "Test Member". They are also
--    asserted NOT to look like email addresses (the console must never leak a
--    founder's address), so no name here contains an '@'.
--
--    ORG PLACEMENT. The ORG_ADMIN sits on the SUB-ORG '2a93054a…', the same org
--    web/e2e/_helpers.ts SUB_ORG_ID pins as "the org-admin fixture's own
--    console" and the same org every fixture this admin owns (cohorts,
--    assignments, exercise provisions, program modules) is scoped to.
--    IT MUST BE ITS OWN ORG, not the parent: the surfaces this account drives
--    read the ADMIN'S OWN organization_id, not the org family. The announcement
--    feed is the one that broke — `AnnouncementReadRepository` scopes the feed
--    to the author's own org, so a root-org author's own broadcast to a sub-org
--    cohort persisted but was invisible to them on reload
--    (announcements.spec.ts). The roi-report outcomes panel reads the same way,
--    which is why the twice-measured founder is parked in the OTHER sub-org.
--    auth.setup.ts's LANDING map already allows this: ORG_ADMIN is the regex
--    /\/app\/admin\/(sub-organizations|organizations\/[0-9a-f-]+)$/ precisely so
--    a sub-org-scoped admin, forwarded into their own org console, still passes.
-- -----------------------------------------------------------------------------
INSERT INTO users (id, email, name, password_hash, role, organization_id, status, user_type, activated_at) VALUES
  ('e2e5eed0-0001-4000-8000-000000000001', 'orgadmin@bvisionry.com',      'Test Org Admin',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'ORG_ADMIN',
   '2a93054a-f352-4220-a321-a84063924096', 'ACTIVE', 'LEADER', now()),

  -- ROLES.DEMO_MEMBER — the auth-flow subject and the populated dashboard:
  -- an evaluated assessment, an in-progress one, and a cohort with live tasks.
  -- Deliberately in NEITHER coach cohort, because coach-booking.spec.ts asserts
  -- this account sees "No coach assigned yet".
  ('e2e5eed0-0001-4000-8000-000000000002', 'member@bvisionry.com',        'Test Member',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'MEMBER',
   '2a93054a-f352-4220-a321-a84063924096', 'ACTIVE', 'LEADER', now()),

  -- ROLES.MEMBER — the EMPTY dashboard variant, on purpose: no assignment, no
  -- submission, no program cohort of its own. founder-dashboard.spec.ts asserts
  -- "You have no assignments yet." for exactly this account, and
  -- competency-matrix.spec.ts asserts it shows zero competency-matrix regions.
  -- Its exercises are the subject of release-flows.spec.ts.
  ('e2e5eed0-0001-4000-8000-000000000003', 'exercise.tester@example.com', 'Exercise Tester',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'MEMBER',
   '2a93054a-f352-4220-a321-a84063924096', 'ACTIVE', 'LEADER', now()),

  -- ROLES.RESET_MEMBER — release-flows.spec.ts drives the real password-reset
  -- loop against this address and resets it back to the same value.
  ('e2e5eed0-0001-4000-8000-000000000004', 'member.e2e@example.com',      'Reset Member',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'MEMBER',
   '2a93054a-f352-4220-a321-a84063924096', 'ACTIVE', 'LEADER', now()),

  -- Not in ROLES, but auth.setup.ts enrols all three by email into its two coach
  -- cohorts and asserts each "is a seeded sub-org member" — so the suite fails at
  -- setup, and therefore entirely, without them.
  -- invite-flow carries the evaluated assessment the coach console shows scores for.
  ('e2e5eed0-0001-4000-8000-000000000005', 'invite-flow@example.com',     'Invite Flow Tester',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'MEMBER',
   '2a93054a-f352-4220-a321-a84063924096', 'ACTIVE', 'LEADER', now()),
  -- loop-test is the founder the coach must NEVER see; it holds an exercise
  -- assignment so the grant boundary is asserted against real data, not absence.
  ('e2e5eed0-0001-4000-8000-000000000006', 'loop-test@example.com',       'Loop Test Member',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'MEMBER',
   '2a93054a-f352-4220-a321-a84063924096', 'ACTIVE', 'LEADER', now()),
  -- coach-console.spec.ts picks this one out of a member picker by the substring
  -- /join-flow-test/ and asserts the display name "Join Flow Tester".
  ('e2e5eed0-0001-4000-8000-000000000007', 'join-flow-test@example.com',  'Join Flow Tester',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'MEMBER',
   '2a93054a-f352-4220-a321-a84063924096', 'ACTIVE', 'LEADER', now()),

  -- The twice-measured founder. Lives in the OTHER sub-org so the org-admin
  -- fixture's own outcomes panel keeps showing the no-movement copy that
  -- roi-report.spec.ts asserts, while the movement case is driven separately
  -- with the super-admin session.
  ('e2e5eed0-0001-4000-8000-000000000008', 'alumni.founder@example.com',  'Alumni Founder',
   '$2a$10$WCoWNvqyUxJqcfrfr.rzZue79yaOw5nOQTmufnn1hPdi4XAW3xzGC', 'MEMBER',
   'e0077f30-1008-4362-9969-a759e8dfd5e8', 'ACTIVE', 'LEADER', now());

-- -----------------------------------------------------------------------------
-- 3. The pipeline — ONE, published, on the id roi-report.spec.ts pins.
--
--    One and not two on purpose: benchmarking, competency-matrix and roi-report
--    all reach the pipeline picker with `selectOption({ index: 1 })`, i.e. "the
--    first real option". With a second published pipeline that index stops
--    meaning anything in particular and three specs become order-dependent.
--
--    Pipelines are platform-global (no organization_id); org scoping is entirely
--    via `assignments`.
-- -----------------------------------------------------------------------------
INSERT INTO pipelines (id, name, description, version, status, created_by) VALUES
  ('072f0c27-cad3-41a8-a055-0dc360da7dee', 'Founder Readiness Assessment',
   'The fixture assessment: two scored pillars and one personal-details pillar.',
   1, 'PUBLISHED', 'e2e5eed0-0001-4000-8000-000000000001');

-- maturity_thresholds_json is left at its column default
-- {"Emerging":[0,59],"Strong":[60,79],"Elite":[80,100]}. The competency matrix
-- places a measurement by EXACT string match of pillar_evaluations.maturity_label
-- against one of these keys — there is no score fallback — so every label written
-- further down is one of those three words verbatim.
-- The PERSONAL pillar is excluded from bands, benchmarks and movement by design;
-- it is here so those exclusions are exercised rather than assumed.
INSERT INTO pillars (id, pipeline_id, name, description, icon_key, weight, display_order, type) VALUES
  ('e2e5eed0-0002-4000-8000-000000000001', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   'Market Insight',   'How well the founder understands their market.', 'target',    1.00, 0, 'STANDARD'),
  ('e2e5eed0-0002-4000-8000-000000000002', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   'Team & Execution', 'How reliably the founder ships.',                'users',     1.00, 1, 'STANDARD'),
  ('e2e5eed0-0002-4000-8000-000000000003', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   'About You',        'Personal details — never scored.',               'user',      1.00, 2, 'PERSONAL');

-- Five questions total. That total is the denominator of the "% complete" the
-- member dashboard renders for an in-progress assessment.
INSERT INTO questions (id, pillar_id, type, prompt_text, display_order, is_required, weight, config_json, system_key) VALUES
  ('e2e5eed0-0003-4000-8000-000000000001', 'e2e5eed0-0002-4000-8000-000000000001',
   'FREE_TEXT', 'Who is your ideal customer, and how do you know?', 0, true, 1.00, NULL, NULL),
  ('e2e5eed0-0003-4000-8000-000000000002', 'e2e5eed0-0002-4000-8000-000000000001',
   'FREE_TEXT', 'What evidence do you have that they will pay?',    1, true, 1.00, NULL, NULL),
  ('e2e5eed0-0003-4000-8000-000000000003', 'e2e5eed0-0002-4000-8000-000000000002',
   'FREE_TEXT', 'Describe the last thing your team shipped.',       0, true, 1.00, NULL, NULL),
  ('e2e5eed0-0003-4000-8000-000000000004', 'e2e5eed0-0002-4000-8000-000000000002',
   'FREE_TEXT', 'What is the biggest gap in your team today?',      1, true, 1.00, NULL, NULL),
  ('e2e5eed0-0003-4000-8000-000000000005', 'e2e5eed0-0002-4000-8000-000000000003',
   'FREE_TEXT', 'What should we call you?',                         0, false, 1.00, NULL, 'FIRST_NAME');

-- -----------------------------------------------------------------------------
-- 4. Cohorts
--    "Alpha Founders" is named to sort first both by position and by name, so
--    roi-report.spec.ts's `cohorts[0]` stays this cohort after auth.setup.ts
--    appends "Coach E2E Cohort" and "Coach E2E Other" to the same sub-org.
-- -----------------------------------------------------------------------------
INSERT INTO cohorts (id, org_id, name, position, status) VALUES
  ('e2e5eed0-0004-4000-8000-000000000001', '2a93054a-f352-4220-a321-a84063924096',
   'Alpha Founders', 0, 'ACTIVE'),
  -- pinned by web/e2e/roi-report.spec.ts MOVEMENT.cohortId / cohortName
  ('fc065e8b-3576-4424-b850-40d6c60065f6', 'e0077f30-1008-4362-9969-a759e8dfd5e8',
   'Cohort 1', 0, 'ACTIVE');

INSERT INTO cohort_members (cohort_id, user_id) VALUES
  ('e2e5eed0-0004-4000-8000-000000000001', 'e2e5eed0-0001-4000-8000-000000000002'),  -- Test Member
  ('e2e5eed0-0004-4000-8000-000000000001', 'e2e5eed0-0001-4000-8000-000000000005'),  -- Invite Flow Tester
  ('fc065e8b-3576-4424-b850-40d6c60065f6', 'e2e5eed0-0001-4000-8000-000000000008');  -- Alumni Founder

-- -----------------------------------------------------------------------------
-- 5. Assessment assignments
--    Every (org, pipeline) pair needs a PROVISION row (user_id IS NULL) beside
--    its member rows — that is the shape V112 established and the two partial
--    unique indexes enforce.
--    max_check_ins > 1 wherever a founder holds more than one submission, so the
--    "Check-in N of M" caption stays truthful.
--
--    created_at IS EXPLICIT AND STAGGERED. Every row below would otherwise take the
--    column default now(), which inside this one BEGIN/COMMIT is transaction_timestamp()
--    — the SAME instant for all five. `AssignmentRepository` serves all three scopes
--    `ORDER BY a.createdAt DESC` with no tiebreak, so tied keys leave the order to the
--    heap and every consumer that reads "the first row" becomes a coin flip. The days
--    predate the oldest submission (181d) so the fixture stays chronologically honest.
-- -----------------------------------------------------------------------------
INSERT INTO assignments (id, pipeline_id, organization_id, assigned_by, user_id, deadline, max_check_ins, created_at) VALUES
  ('e2e5eed0-0005-4000-8000-000000000001', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   '2a93054a-f352-4220-a321-a84063924096', 'e2e5eed0-0001-4000-8000-000000000001', NULL, NULL, 3,
   now() - interval '200 days'),
  ('e2e5eed0-0005-4000-8000-000000000002', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   '2a93054a-f352-4220-a321-a84063924096', 'e2e5eed0-0001-4000-8000-000000000001',
   'e2e5eed0-0001-4000-8000-000000000002', NULL, 3, now() - interval '199 days'),
  ('e2e5eed0-0005-4000-8000-000000000003', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   '2a93054a-f352-4220-a321-a84063924096', 'e2e5eed0-0001-4000-8000-000000000001',
   'e2e5eed0-0001-4000-8000-000000000005', NULL, 3, now() - interval '198 days'),
  ('e2e5eed0-0005-4000-8000-000000000004', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   'e0077f30-1008-4362-9969-a759e8dfd5e8', 'e2e5eed0-0001-4000-8000-000000000001', NULL, NULL, 3,
   now() - interval '197 days'),
  ('e2e5eed0-0005-4000-8000-000000000005', '072f0c27-cad3-41a8-a055-0dc360da7dee',
   'e0077f30-1008-4362-9969-a759e8dfd5e8', 'e2e5eed0-0001-4000-8000-000000000001',
   'e2e5eed0-0001-4000-8000-000000000008', NULL, 3, now() - interval '196 days');

-- -----------------------------------------------------------------------------
-- 6. Submissions
--    `status='EVALUATED'` plus a non-null `evaluated_at` is what the member
--    dashboard, the competency matrix, the benchmarks and the ROI report all
--    filter on; none of them counts an IN_PROGRESS row, so the pair below on
--    assignment ...02 does NOT make Test Member look twice-assessed.
--    created_at is set explicitly because "latest" is per-assignment newest.
--    submitted_at is set explicitly because the ROI report orders intake vs
--    latest by COALESCE(submitted_at, created_at) with the row id as tiebreak —
--    equal timestamps would make which row is "intake" unpredictable.
-- -----------------------------------------------------------------------------
INSERT INTO submissions (id, assignment_id, user_id, status, started_at, submitted_at, evaluated_at, created_at, updated_at) VALUES
  -- Test Member: measured once...
  ('e2e5eed0-0006-4000-8000-000000000001', 'e2e5eed0-0005-4000-8000-000000000002',
   'e2e5eed0-0001-4000-8000-000000000002', 'EVALUATED',
   now() - interval '46 days', now() - interval '45 days', now() - interval '45 days',
   now() - interval '46 days', now() - interval '45 days'),
  -- ...and currently part-way through a second check-in, which is what puts a
  -- "N% complete" next action on their dashboard.
  ('e2e5eed0-0006-4000-8000-000000000002', 'e2e5eed0-0005-4000-8000-000000000002',
   'e2e5eed0-0001-4000-8000-000000000002', 'IN_PROGRESS',
   now() - interval '2 days', NULL, NULL,
   now() - interval '2 days', now() - interval '2 days'),

  -- Invite Flow Tester: measured once. The coach console reads pillar scores off
  -- this one.
  ('e2e5eed0-0006-4000-8000-000000000003', 'e2e5eed0-0005-4000-8000-000000000003',
   'e2e5eed0-0001-4000-8000-000000000005', 'EVALUATED',
   now() - interval '31 days', now() - interval '30 days', now() - interval '30 days',
   now() - interval '31 days', now() - interval '30 days'),

  -- Alumni Founder: intake...
  ('e2e5eed0-0006-4000-8000-000000000004', 'e2e5eed0-0005-4000-8000-000000000005',
   'e2e5eed0-0001-4000-8000-000000000008', 'EVALUATED',
   now() - interval '181 days', now() - interval '180 days', now() - interval '180 days',
   now() - interval '181 days', now() - interval '180 days'),
  -- ...and re-measured. The ONLY founder in the fixture with a second evaluated
  -- submission, and therefore the only source of real movement.
  ('e2e5eed0-0006-4000-8000-000000000005', 'e2e5eed0-0005-4000-8000-000000000005',
   'e2e5eed0-0001-4000-8000-000000000008', 'EVALUATED',
   now() - interval '21 days', now() - interval '20 days', now() - interval '20 days',
   now() - interval '21 days', now() - interval '20 days');

-- Two of five questions answered on the open check-in ⇒ "40% complete".
INSERT INTO answers (id, submission_id, question_id, response_text) VALUES
  ('e2e5eed0-0009-4000-8000-000000000001', 'e2e5eed0-0006-4000-8000-000000000002',
   'e2e5eed0-0003-4000-8000-000000000001',
   'Operations leads at seed-stage logistics startups; we interviewed twelve of them last month.'),
  ('e2e5eed0-0009-4000-8000-000000000002', 'e2e5eed0-0006-4000-8000-000000000002',
   'e2e5eed0-0003-4000-8000-000000000002',
   'Four signed letters of intent and two paid pilots running since the spring.');

-- -----------------------------------------------------------------------------
-- 7. Evaluations
--    maturity_label MUST be byte-identical to a key of the pillar's
--    maturity_thresholds_json or the competency matrix drops the measurement
--    off-axis. The two scored pillars are evaluated on BOTH of the Alumni
--    Founder's submissions, because the ROI movement query self-joins on
--    pillar_id across intake and latest — a pillar missing from either end
--    produces no delta.
--    The overall_summaries row is not optional: MemberResultsService 404s the
--    whole results payload without one, which would take the dashboard's
--    "Pillar snapshot" and the competency matrix down with it.
-- -----------------------------------------------------------------------------
INSERT INTO pillar_evaluations (id, submission_id, pillar_id, score_percentage, maturity_label, ai_score_means, evaluated_at) VALUES
  -- Test Member (once)
  ('e2e5eed0-0007-4000-8000-000000000001', 'e2e5eed0-0006-4000-8000-000000000001',
   'e2e5eed0-0002-4000-8000-000000000001', 68.00, 'Strong',
   'Clear customer definition, thin evidence of willingness to pay.', now() - interval '45 days'),
  ('e2e5eed0-0007-4000-8000-000000000002', 'e2e5eed0-0006-4000-8000-000000000001',
   'e2e5eed0-0002-4000-8000-000000000002', 54.00, 'Emerging',
   'Ships steadily but the delivery load sits on one person.', now() - interval '45 days'),

  -- Invite Flow Tester (once)
  ('e2e5eed0-0007-4000-8000-000000000003', 'e2e5eed0-0006-4000-8000-000000000003',
   'e2e5eed0-0002-4000-8000-000000000001', 82.00, 'Elite',
   'Segment, pain and pricing are all evidenced.', now() - interval '30 days'),
  ('e2e5eed0-0007-4000-8000-000000000004', 'e2e5eed0-0006-4000-8000-000000000003',
   'e2e5eed0-0002-4000-8000-000000000002', 61.00, 'Strong',
   'A real team with a real cadence; hiring is the constraint.', now() - interval '30 days'),

  -- Alumni Founder, intake
  ('e2e5eed0-0007-4000-8000-000000000005', 'e2e5eed0-0006-4000-8000-000000000004',
   'e2e5eed0-0002-4000-8000-000000000001', 41.00, 'Emerging',
   'Market framed by intuition rather than evidence.', now() - interval '180 days'),
  ('e2e5eed0-0007-4000-8000-000000000006', 'e2e5eed0-0006-4000-8000-000000000004',
   'e2e5eed0-0002-4000-8000-000000000002', 38.00, 'Emerging',
   'No repeatable delivery rhythm yet.', now() - interval '180 days'),

  -- Alumni Founder, latest — both pillars up, so the report shows a signed delta
  ('e2e5eed0-0007-4000-8000-000000000007', 'e2e5eed0-0006-4000-8000-000000000005',
   'e2e5eed0-0002-4000-8000-000000000001', 73.00, 'Strong',
   'Segmentation now grounded in interviews and pilot data.', now() - interval '20 days'),
  ('e2e5eed0-0007-4000-8000-000000000008', 'e2e5eed0-0006-4000-8000-000000000005',
   'e2e5eed0-0002-4000-8000-000000000002', 66.00, 'Strong',
   'Weekly shipping cadence held for two quarters.', now() - interval '20 days');

INSERT INTO overall_summaries (id, submission_id, overall_score_percentage, summary_narrative, strengths, development_areas, core_pattern, moving_forward_narrative, generated_at) VALUES
  ('e2e5eed0-0008-4000-8000-000000000001', 'e2e5eed0-0006-4000-8000-000000000001', 61.00,
   'A clear picture of who you serve, held back by how much of the delivery still runs through you.',
   '["Customer definition is specific and evidenced"]'::jsonb,
   '["Delivery depends on a single person"]'::jsonb,
   'Clarity outside the building, bottleneck inside it.',
   'Write down the two decisions only you can make, and delegate the rest.',
   now() - interval '45 days'),
  ('e2e5eed0-0008-4000-8000-000000000002', 'e2e5eed0-0006-4000-8000-000000000003', 71.50,
   'Strong commercial instincts backed by evidence; the constraint is people, not demand.',
   '["Pricing is tested, not assumed"]'::jsonb,
   '["Hiring plan lags the pipeline"]'::jsonb,
   'Demand is proven; capacity is not.',
   'Start the two hires you have already scoped.',
   now() - interval '30 days'),
  ('e2e5eed0-0008-4000-8000-000000000003', 'e2e5eed0-0006-4000-8000-000000000004', 39.50,
   'Early. Conviction is ahead of evidence in both the market and the delivery story.',
   '["Genuine domain conviction"]'::jsonb,
   '["No customer evidence yet","No delivery rhythm"]'::jsonb,
   'Conviction running ahead of evidence.',
   'Run ten customer interviews before building anything else.',
   now() - interval '180 days'),
  ('e2e5eed0-0008-4000-8000-000000000004', 'e2e5eed0-0006-4000-8000-000000000005', 69.50,
   'Six months on, both the market case and the delivery cadence are evidenced rather than asserted.',
   '["Interview-grounded segmentation","Sustained shipping cadence"]'::jsonb,
   '["Pricing still single-tier"]'::jsonb,
   'Evidence has caught up with conviction.',
   'Test a second price point with the pilot cohort.',
   now() - interval '20 days');

-- -----------------------------------------------------------------------------
-- 8. Program flow — what makes the member dashboard say "1 of 2 tasks".
--    Only program_tasks with status='LIVE' count; DRAFT tasks are invisible to
--    learners and excluded from the denominator. lock_mode='UNLOCKED' keeps the
--    module open regardless of the drip settings, and no program_settings row is
--    needed — the mapper supplies defaults for a missing one.
-- -----------------------------------------------------------------------------
INSERT INTO program_modules (id, org_id, cohort_id, name, summary, position, lock_mode, assign_mode) VALUES
  ('e2e5eed0-000a-4000-8000-000000000001', '2a93054a-f352-4220-a321-a84063924096',
   'e2e5eed0-0004-4000-8000-000000000001', 'Week 1 — Foundations',
   'Get the market case written down before anything is built.', 0, 'UNLOCKED', 'ALL');

INSERT INTO program_tasks (id, module_id, name, due_date, status, ai_draft, position) VALUES
  ('e2e5eed0-000b-4000-8000-000000000001', 'e2e5eed0-000a-4000-8000-000000000001',
   'Define your problem statement', NULL, 'LIVE', false, 0),
  ('e2e5eed0-000b-4000-8000-000000000002', 'e2e5eed0-000a-4000-8000-000000000001',
   'Map your first ten customers',   NULL, 'LIVE', false, 1);

INSERT INTO program_task_fields (id, task_id, field_type, required, position, config) VALUES
  ('e2e5eed0-000c-4000-8000-000000000001', 'e2e5eed0-000b-4000-8000-000000000001',
   'LONG', true, 0, '{"question":"In one paragraph, whose problem are you solving?"}'::jsonb),
  ('e2e5eed0-000c-4000-8000-000000000002', 'e2e5eed0-000b-4000-8000-000000000002',
   'LONG', true, 0, '{"question":"List ten named prospects and why each would buy."}'::jsonb);

-- One of the two tasks submitted ⇒ "1 of 2 tasks", which is neither the empty
-- state nor the complete state, so both branches of the progress UI are honest.
INSERT INTO program_submissions (id, task_id, user_id, status, answers, saved_at, submitted_at, points_awarded) VALUES
  ('e2e5eed0-000d-4000-8000-000000000001', 'e2e5eed0-000b-4000-8000-000000000001',
   'e2e5eed0-0001-4000-8000-000000000002', 'SUBMITTED',
   '{"e2e5eed0-000c-4000-8000-000000000001":"Seed-stage logistics operators lose a day a week to manual reconciliation."}'::jsonb,
   now() - interval '10 days', now() - interval '10 days', 10);

-- -----------------------------------------------------------------------------
-- 9. Exercises
--    A member's /app/exercises list is driven by exercise_submissions, NOT by
--    exercise_assignments, and nothing materialises a submission lazily — the
--    assign path creates it eagerly. So every member assignment below gets its
--    submission row here too, or the member sees an empty list.
--    The org PROVISION row (user_id IS NULL) is what lets an ORG_ADMIN
--    distribute the template at all, and any single assignment row — provision
--    included — is what flips `structureLocked` and renders the
--    "columns can no longer be added or removed" banner in the builder.
-- -----------------------------------------------------------------------------
--    created_at is explicit here and on exercise_assignments below — see the note on
--    `assignments`. Same rule throughout this section: "Competitor Analysis Test" is
--    the NEWER row everywhere, so it leads every `created_at DESC` list it appears in.
INSERT INTO exercise_templates (id, name, description, status, created_by, allow_add_rows, starter_rows, created_at) VALUES
  ('e2e5eed0-000e-4000-8000-000000000001', 'Session Log',
   'One row per coaching session: what was discussed and what was agreed.',
   'PUBLISHED', 'e2e5eed0-0001-4000-8000-000000000001', true, NULL, now() - interval '30 days'),
  ('e2e5eed0-000e-4000-8000-000000000002', 'Competitor Analysis Test',
   'Name the three competitors you lose to, and what you would have to change to stop losing.',
   'PUBLISHED', 'e2e5eed0-0001-4000-8000-000000000001', true, NULL, now() - interval '29 days');

INSERT INTO exercise_columns (id, template_id, name, type, config_json, display_order, is_required, is_locked) VALUES
  ('e2e5eed0-000f-4000-8000-000000000001', 'e2e5eed0-000e-4000-8000-000000000001',
   'Date',            'DATE',      NULL, 0, true,  false),
  ('e2e5eed0-000f-4000-8000-000000000002', 'e2e5eed0-000e-4000-8000-000000000001',
   'What we agreed',  'LONG_TEXT', NULL, 1, true,  false),
  ('e2e5eed0-000f-4000-8000-000000000003', 'e2e5eed0-000e-4000-8000-000000000002',
   'Competitor',      'TEXT',      NULL, 0, true,  false),
  ('e2e5eed0-000f-4000-8000-000000000004', 'e2e5eed0-000e-4000-8000-000000000002',
   'Where they win',  'LONG_TEXT', NULL, 1, true,  false),
  ('e2e5eed0-000f-4000-8000-000000000005', 'e2e5eed0-000e-4000-8000-000000000002',
   'Our answer',      'LONG_TEXT', NULL, 2, false, false);

-- created_at CARRIES A SPEC ASSERTION and must stay staggered, newest last.
-- `ExerciseAssignmentService` serves scope=ALL with
-- `findByOrganizationIdOrderByCreatedAtDesc`, and assign-exercise-dialog.tsx builds an
-- ORG_ADMIN's exercise radio list from that list's provision rows IN ORDER.
-- release-flows.spec.ts clicks `dialog.getByRole("radio").first()`, i.e. the newest
-- provision, and then asserts the "already assigned" badge — which only renders when
-- the SELECTED template has member rows. So the newest provision must be
-- "Competitor Analysis Test", the only template that has any. Left to the column
-- default all four rows would share one transaction_timestamp(), the sort key would
-- tie, and the winner would be whichever row the heap happened to return.
INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by, deadline, created_at) VALUES
  -- provisions
  ('e2e5eed0-0010-4000-8000-000000000001', 'e2e5eed0-000e-4000-8000-000000000001',
   '2a93054a-f352-4220-a321-a84063924096', NULL, 'e2e5eed0-0001-4000-8000-000000000001', NULL,
   now() - interval '28 days'),
  ('e2e5eed0-0010-4000-8000-000000000002', 'e2e5eed0-000e-4000-8000-000000000002',
   '2a93054a-f352-4220-a321-a84063924096', NULL, 'e2e5eed0-0001-4000-8000-000000000001', NULL,
   now() - interval '27 days'),
  -- member rows. The pair is what makes the admin assign dialog show its
  -- "already assigned" badge, which release-flows.spec.ts asserts.
  ('e2e5eed0-0010-4000-8000-000000000003', 'e2e5eed0-000e-4000-8000-000000000002',
   '2a93054a-f352-4220-a321-a84063924096', 'e2e5eed0-0001-4000-8000-000000000003',
   'e2e5eed0-0001-4000-8000-000000000001', NULL, now() - interval '26 days'),
  ('e2e5eed0-0010-4000-8000-000000000004', 'e2e5eed0-000e-4000-8000-000000000002',
   '2a93054a-f352-4220-a321-a84063924096', 'e2e5eed0-0001-4000-8000-000000000006',
   'e2e5eed0-0001-4000-8000-000000000001', NULL, now() - interval '25 days');

INSERT INTO exercise_submissions (id, assignment_id, user_id, status, last_saved_at, submitted_at, reviewed_at, version) VALUES
  -- REVIEWED is where release-flows.spec.ts's exercise lifecycle starts and ends
  -- — it drives the cycle forward and restores this state.
  ('e2e5eed0-0011-4000-8000-000000000001', 'e2e5eed0-0010-4000-8000-000000000003',
   'e2e5eed0-0001-4000-8000-000000000003', 'REVIEWED',
   now() - interval '9 days', now() - interval '9 days', now() - interval '8 days', 0),
  -- The hidden founder's started exercise: the coach console's boundary test
  -- asserts against real data rather than absence.
  ('e2e5eed0-0011-4000-8000-000000000002', 'e2e5eed0-0010-4000-8000-000000000004',
   'e2e5eed0-0001-4000-8000-000000000006', 'IN_PROGRESS',
   now() - interval '4 days', NULL, NULL, 0);

-- cells maps column id (as a string UUID) to the entered value.
INSERT INTO exercise_rows (id, submission_id, display_order, cells, is_starter) VALUES
  ('e2e5eed0-0012-4000-8000-000000000001', 'e2e5eed0-0011-4000-8000-000000000001', 0,
   '{"e2e5eed0-000f-4000-8000-000000000003":"Ledgerline",
     "e2e5eed0-000f-4000-8000-000000000004":"Native integrations with the two accounting suites our buyers already run.",
     "e2e5eed0-000f-4000-8000-000000000005":"Ship the reconciliation import before the next renewal window."}'::jsonb,
   false),
  ('e2e5eed0-0012-4000-8000-000000000002', 'e2e5eed0-0011-4000-8000-000000000001', 1,
   '{"e2e5eed0-000f-4000-8000-000000000003":"Freightbook",
     "e2e5eed0-000f-4000-8000-000000000004":"Cheaper, and bundled with a carrier deal.",
     "e2e5eed0-000f-4000-8000-000000000005":"Compete on time-to-value, not price."}'::jsonb,
   false),
  ('e2e5eed0-0012-4000-8000-000000000003', 'e2e5eed0-0011-4000-8000-000000000002', 0,
   '{"e2e5eed0-000f-4000-8000-000000000003":"Ledgerline"}'::jsonb,
   false);

-- -----------------------------------------------------------------------------
-- 10. The survey
--     /app/admin/surveys is SUPER_ADMIN-only and unscoped by org.
--     release-flows.spec.ts asserts this survey by name and asserts at least one
--     "Delete response" button — one row of survey_responses is one button.
--     created_by is resolved by email because migration V16 mints the super
--     admin with gen_random_uuid(), so its id is not a literal this file can pin.
--     cookie_id / ip_hash stay NULL so neither response is flagged a duplicate.
-- -----------------------------------------------------------------------------
INSERT INTO surveys (id, name, description, status, visibility, respondent_email_mode, respondent_name_mode, published_at, created_by)
SELECT 'e2e5eed0-0013-4000-8000-000000000001', 'Ponytail Validation (temp)',
       'Fixture survey for the release-flows suite.',
       'PUBLISHED', 'PRIVATE', 'NONE', 'NONE', now() - interval '14 days', u.id
  FROM users u WHERE u.email = 'admin@bvisionry.com';

INSERT INTO survey_pillars (id, survey_id, name, description, display_order) VALUES
  ('e2e5eed0-0014-4000-8000-000000000001', 'e2e5eed0-0013-4000-8000-000000000001',
   'Experience', 'How the assessment felt to take.', 0);

INSERT INTO survey_questions (id, pillar_id, type, prompt_text, display_order, is_required, config_json) VALUES
  ('e2e5eed0-0015-4000-8000-000000000001', 'e2e5eed0-0014-4000-8000-000000000001',
   'LIKERT',     'How useful was the assessment?', 0, true,  '{"min":1,"max":5}'::jsonb),
  ('e2e5eed0-0015-4000-8000-000000000002', 'e2e5eed0-0014-4000-8000-000000000001',
   'SHORT_TEXT', 'What would you change?',         1, false, NULL);

INSERT INTO survey_responses (id, survey_id, submitted_at, source) VALUES
  ('e2e5eed0-0016-4000-8000-000000000001', 'e2e5eed0-0013-4000-8000-000000000001',
   now() - interval '12 days', 'PUBLIC_LINK'),
  ('e2e5eed0-0016-4000-8000-000000000002', 'e2e5eed0-0013-4000-8000-000000000001',
   now() - interval '11 days', 'PUBLIC_LINK');

INSERT INTO survey_answers (id, response_id, question_id, response_text, selected_value, numeric_value) VALUES
  ('e2e5eed0-0017-4000-8000-000000000001', 'e2e5eed0-0016-4000-8000-000000000001',
   'e2e5eed0-0015-4000-8000-000000000001', NULL, '4', 4.00),
  ('e2e5eed0-0017-4000-8000-000000000002', 'e2e5eed0-0016-4000-8000-000000000001',
   'e2e5eed0-0015-4000-8000-000000000002', 'Fewer open-text questions near the end.', NULL, NULL),
  ('e2e5eed0-0017-4000-8000-000000000003', 'e2e5eed0-0016-4000-8000-000000000002',
   'e2e5eed0-0015-4000-8000-000000000001', NULL, '5', 5.00),
  ('e2e5eed0-0017-4000-8000-000000000004', 'e2e5eed0-0016-4000-8000-000000000002',
   'e2e5eed0-0015-4000-8000-000000000002', 'The pillar summaries were the useful part.', NULL, NULL);

COMMIT;
