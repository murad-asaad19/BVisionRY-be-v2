-- =============================================================================
-- Demo-cohort seed for the redesigned cohort surfaces (DATA ONLY).
--
-- APPLY AFTER `e2e-seed.sql` — it reads rows that file creates (see the guard).
--
-- RUN (lane 1 — NEVER the dev DB on :5432):
--   docker exec -i bvisionry-lane1-db-1 psql -U bvisionry -d bvisionry \
--     -v ON_ERROR_STOP=1 < backend/tools/e2e-seed/seed-demo-cohort.sql
--
-- Other lanes: swap the container name (bvisionry-lane<N>-db-1). After a
-- `sandbox.sh reset <N>` just run it again — it is idempotent (fixed
-- 'de300000-' UUIDs + md5-derived child ids) and never deletes a row.
--
-- CI applies it the same way, as the second seed step in the web repo's
-- `.github/workflows/e2e.yml`. It lived unversioned at
-- docker/sandbox/seed-demo-cohort.sql — above both git repos — until it moved
-- here, which is why cohort-view.spec.ts and a11y-app.spec.ts could not pass
-- on a runner.
--
-- SELF-SUFFICIENT (this is the whole point of the file)
-- -----------------------------------------------------
-- An earlier revision pointed at a pipeline, a survey, an exercise template,
-- two coaches and five founders that only ever existed in an uncommitted
-- pg_dump of the operator's dev stack. One `sandbox.sh reset` and the seed
-- aborted half-way through on a foreign-key error, taking the demo cohorts
-- with it and leaving no way to rebuild them. So:
--
--   CREATED BY THIS FILE, on its own 'de300000-' ids:
--     * pipeline "Founder Mindset Assessment" — 6 pillars x 2 questions,
--       PUBLISHED, enough structure for a real evaluated submission
--     * survey "Mid-programme Reflection (Demo)" — 1 pillar, 2 questions
--     * exercise template "Map the Competitive Field (Demo)" + 3 columns
--     * 2 COACH users and 5 MEMBER founders (password `root`)
--     * both cohorts, their cohort_orgs assignment to the org below, their
--       modules, tasks, fields, sessions, announcement
--
--   RELIED ON, and asserted up front so drift fails loudly (see the guard):
--     * org 2a93054a…4096 — "General" under "Test Organization"
--       (backend/tools/e2e-seed/e2e-seed.sql)
--     * user orgadmin@bvisionry.com — author / assigned_by  (same file)
--     * catalog course "leadership-essentials-new-managers" — Flyway V77,
--       addressed by its md5 id rather than a copied literal
--
-- Nothing else is read from ambient data, so the file runs on a lane holding
-- only a Flyway-migrated schema plus the e2e fixtures.
--
-- WHAT IT BUILDS
--   * cohort "Demo Founders" (LAUNCHED) → 4 pillar modules, typed task spine
--     (LESSON/EXERCISE/COURSE/SURVEY + BASELINE/CHECKIN/DISTANCE assessment
--     milestones), 5 founders with four different progress stories, 4 sessions
--     with attendance, 1 announcement.
--   * cohort "Delta Alumni (2025)" (COMPLETED) → 2 modules, 4 lessons, all
--     submitted, so the closing state has content.
--   * bodies for the nine V77 catalog demo courses, whose 70 lessons ship with
--     every payload column NULL (see "Catalog course content" below).
--
-- WORKSHOP IS NO LONGER A COHORT TASK TYPE (V172): the type gated the drip on
-- work a member with no workshop team could never finish, so the operator
-- removed it from ck_program_tasks_task_type. The standalone workshop below is
-- still minted — the Workshops console is untouched — but nothing on the
-- cohort's spine references it.
--
-- A COHORT IS A PLATFORM ARTIFACT (V171): cohorts / program_modules / sessions
-- lost their org_id, and participation moved to the cohort_orgs join table.
-- Every read in the app is org-scoped through that table, so the two rows this
-- file writes into it are what make the demo cohorts visible at all.
--
-- ONE KNOWN INTERACTION, stated rather than hidden: the demo pipeline is a
-- SECOND published pipeline. e2e-seed.sql deliberately ships exactly one,
-- because benchmarking / competency-matrix / roi-report all reach the pipeline
-- picker with `selectOption({index: 1})`. On a lane that holds nothing but the
-- e2e fixtures, running this seed BEFORE those specs would make that index
-- ambiguous. This is a demo seed — apply it after the suite, or not on a lane
-- you are about to gate on. The demo cannot have an assessment milestone
-- without a published pipeline, and faking an unpublished one would be worse.
--
-- POSITIONS ARE LOAD-BEARING. Both cohorts sit at position 10/11 so they never
-- become the org's FIRST cohort — roi-report.spec.ts and announcements.spec.ts
-- both read `cohorts[0]` (ordered by position, name). The demo exercise
-- template and its org provision are back-dated 400 days for the same reason:
-- release-flows.spec.ts clicks the NEWEST provision in the assign dialog.
-- =============================================================================

DO $seed$
DECLARE
    org        uuid := '2a93054a-f352-4220-a321-a84063924096'; -- General (sub-org of Test Organization)

    -- Everything below is minted by this file.
    pipe       uuid := 'de300000-0100-4000-8000-000000000001'; -- Founder Mindset Assessment
    v_survey   uuid := 'de300000-0200-4000-8000-000000000001'; -- Mid-programme Reflection (Demo)
    ex_tpl     uuid := 'de300000-0300-4000-8000-000000000001'; -- Map the Competitive Field (Demo)
    wshop      uuid := 'de300000-0600-4000-8000-000000000001'; -- Founder Positioning Sprint (Demo)
    w_ex       uuid := 'de300000-0601-4000-8000-000000000001'; -- its one exercise
    w_team     uuid := 'de300000-0602-4000-8000-000000000001'; -- its one team
    cohort_a   uuid := 'de300000-0400-4000-8000-000000000001'; -- Demo Founders (LAUNCHED)
    cohort_d   uuid := 'de300000-0400-4000-8000-000000000002'; -- Delta Alumni (2025) (COMPLETED)

    v_coach1   uuid := 'de300000-0500-4000-8000-000000000001'; -- Priya Hale     (cohort coach)
    v_coach2   uuid := 'de300000-0500-4000-8000-000000000002'; -- Tunde Okafor   (1:1 coach)

    fa uuid := 'de300000-0501-4000-8000-000000000001'; -- Mara Reyes     — full story
    fb uuid := 'de300000-0501-4000-8000-000000000002'; -- Samir Iqbal    — mid progress
    fc uuid := 'de300000-0501-4000-8000-000000000003'; -- Elena Novak    — barely started
    fd uuid := 'de300000-0501-4000-8000-000000000004'; -- Yann Baptiste  — overdue, uncovered
    fe uuid := 'de300000-0501-4000-8000-000000000005'; -- Akua Osei      — light activity

    -- Relied on. Resolved, then asserted by the guard below.
    v_course   uuid := md5('course:leadership-essentials-new-managers')::uuid;  -- Flyway V77
    admin_id   uuid;

    m1 uuid := 'de300000-0000-4000-8000-000000000001';
    m2 uuid := 'de300000-0000-4000-8000-000000000002';
    m3 uuid := 'de300000-0000-4000-8000-000000000003';
    m4 uuid := 'de300000-0000-4000-8000-000000000004';
    dm1 uuid := 'de300000-0000-4000-8000-000000000011';
    dm2 uuid := 'de300000-0000-4000-8000-000000000012';

    t_base  uuid := 'de300000-0001-4000-8000-000000000008';
    t_check uuid := 'de300000-0001-4000-8000-000000000004';
    t_dist  uuid := 'de300000-0001-4000-8000-000000000009';
    l1 uuid := 'de300000-0001-4000-8000-000000000001';
    l2 uuid := 'de300000-0001-4000-8000-000000000002';
    t_ex uuid := 'de300000-0001-4000-8000-000000000003';
    t_co uuid := 'de300000-0001-4000-8000-000000000005';
    t_su uuid := 'de300000-0001-4000-8000-000000000006';
    l3 uuid := 'de300000-0001-4000-8000-000000000007';
    -- ...00a was the WORKSHOP task. V172 dropped the type and deleted the row;
    -- the id stays retired rather than reused.

    -- bcrypt(10) of `root` — the documented local password, same hash
    -- backend/tools/e2e-seed/e2e-seed.sql uses.
    pw text := '$2a$10$x6g8Db/WWYHlzZ4xQRFmKe.qOhoMKp0xgUT94UCIEUDI0lGa6Mid.';

    r          record;
    v_assign   uuid;
    v_sub      uuid;
    v_ea       uuid;
BEGIN

-- ============================================================== the guard ===
-- Fail LOUDLY and EARLY on the three things this file does not build itself,
-- rather than half-way through on an opaque foreign-key error.
IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = org) THEN
    RAISE EXCEPTION
        'seed-demo-cohort.sql: missing organization % ("General", the sub-org of "Test Organization"). This seed mints its own pipeline, survey, exercise template, coaches and founders, but it has to attach them to that org. Fix: boot the backend once so Flyway migrates the schema, then apply backend/tools/e2e-seed/e2e-seed.sql, then re-run this file.', org;
END IF;

SELECT id INTO admin_id FROM users WHERE email = 'orgadmin@bvisionry.com';
IF admin_id IS NULL THEN
    RAISE EXCEPTION
        'seed-demo-cohort.sql: missing user orgadmin@bvisionry.com — it is the author of the seeded sessions and announcement and the assigned_by of every assignment. Fix: apply backend/tools/e2e-seed/e2e-seed.sql, then re-run this file.';
END IF;

IF NOT EXISTS (SELECT 1 FROM course WHERE id = v_course AND state = 'PUBLISHED') THEN
    RAISE EXCEPTION
        'seed-demo-cohort.sql: missing catalog course "leadership-essentials-new-managers" (expected id %, = md5(''course:<slug>'')). It ships with Flyway migration V77__catalog_seed.sql. Fix: boot the backend once so migrations run, then re-run this file.', v_course;
END IF;

-- Both platform consoles are OPT-IN per organization: CohortRepository
-- .findOrgProgramRows derives inProgramFlow / inWorkshops as EXISTS subqueries
-- over program_surface_orgs, and program-flow-shell.tsx filters on them. Without
-- these two rows the seeded cohorts and the seeded workshop exist but are
-- unreachable from /app/admin/program-flow and /app/admin/workshops until
-- someone thinks to click "Add organization" — which nobody would guess.
-- Same semantic as the repository's own addToSurface: ON CONFLICT DO NOTHING
-- against the (org_id, surface) primary key.
INSERT INTO program_surface_orgs (org_id, surface)
VALUES (org, 'PROGRAM_FLOW'), (org, 'WORKSHOPS')
ON CONFLICT DO NOTHING;

-- ====================================================== things it owns =======

-- ---------------------------------------------------------------- people ----
-- Demo identities on their own '@bvisionry.demo' domain: nothing here can
-- collide with an e2e fixture account, and the coach console's
-- "never render an @example.com address" assertion cannot see them either.
INSERT INTO users (id, email, name, password_hash, role, organization_id, status, user_type, activated_at) VALUES
 (v_coach1, 'demo.coach.hale@bvisionry.demo',      'Priya Hale',    pw, 'COACH',  org, 'ACTIVE', 'LEADER', now()),
 (v_coach2, 'demo.coach.okafor@bvisionry.demo',    'Tunde Okafor',  pw, 'COACH',  org, 'ACTIVE', 'LEADER', now()),
 (fa,       'demo.founder.reyes@bvisionry.demo',   'Mara Reyes',    pw, 'MEMBER', org, 'ACTIVE', 'LEADER', now()),
 (fb,       'demo.founder.iqbal@bvisionry.demo',   'Samir Iqbal',   pw, 'MEMBER', org, 'ACTIVE', 'LEADER', now()),
 (fc,       'demo.founder.novak@bvisionry.demo',   'Elena Novak',   pw, 'MEMBER', org, 'ACTIVE', 'LEADER', now()),
 (fd,       'demo.founder.baptiste@bvisionry.demo','Yann Baptiste', pw, 'MEMBER', org, 'ACTIVE', 'LEADER', now()),
 (fe,       'demo.founder.osei@bvisionry.demo',    'Akua Osei',     pw, 'MEMBER', org, 'ACTIVE', 'LEADER', now())
ON CONFLICT (id) DO UPDATE SET
    email = EXCLUDED.email, name = EXCLUDED.name, password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role, organization_id = EXCLUDED.organization_id,
    status = EXCLUDED.status, user_type = EXCLUDED.user_type, updated_at = now();

-- -------------------------------------------------------------- pipeline ----
-- The assessment behind the BASELINE / CHECKIN / DISTANCE milestones. Six
-- scored pillars, two free-text questions each — enough for a real evaluated
-- submission with a per-pillar breakdown and an FRI number.
INSERT INTO pipelines (id, name, description, version, status, created_by)
VALUES (pipe, 'Founder Mindset Assessment',
        'The demo assessment behind the cohort''s baseline and distance milestones: six founder-mindset pillars, scored.',
        1, 'PUBLISHED', admin_id)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, description = EXCLUDED.description,
    status = EXCLUDED.status, updated_at = now();

-- Pillar names mirror the modules' pillar_label so the board's pillar column
-- and the curriculum spine tell the same story.
INSERT INTO pillars (id, pipeline_id, name, description, icon_key, weight, display_order, type) VALUES
 ('de300000-0101-4000-8000-000000000001', pipe, 'Vision Clarity',
  'How specific and defensible the destination is.',            'target',    1.00, 0, 'STANDARD'),
 ('de300000-0101-4000-8000-000000000002', pipe, 'Discipline',
  'Whether the weekly operating rhythm survives a bad week.',   'calendar',  1.00, 1, 'STANDARD'),
 ('de300000-0101-4000-8000-000000000003', pipe, 'Energy & Motivation',
  'The fuel the plan quietly depends on.',                      'zap',       1.00, 2, 'STANDARD'),
 ('de300000-0101-4000-8000-000000000004', pipe, 'Focus & Flow',
  'How ruthlessly the list gets cut to one thing.',             'crosshair', 1.00, 3, 'STANDARD'),
 ('de300000-0101-4000-8000-000000000005', pipe, 'Resilience',
  'What happens to the plan after the first real setback.',     'shield',    1.00, 4, 'STANDARD'),
 ('de300000-0101-4000-8000-000000000006', pipe, 'Ownership',
  'Whether outcomes are owned or narrated.',                    'users',     1.00, 5, 'STANDARD')
ON CONFLICT (id) DO UPDATE SET
    pipeline_id = EXCLUDED.pipeline_id, name = EXCLUDED.name,
    description = EXCLUDED.description, weight = EXCLUDED.weight,
    display_order = EXCLUDED.display_order, type = EXCLUDED.type, updated_at = now();
-- maturity_thresholds_json stays at its column default
-- {"Emerging":[0,59],"Strong":[60,79],"Elite":[80,100]}; the evaluation loop
-- below reads the labels back out of it so they always match by exact string.

INSERT INTO questions (id, pillar_id, type, prompt_text, display_order, is_required, weight)
SELECT md5('demo:question:' || p.id::text || ':' || q.n)::uuid, p.id, 'FREE_TEXT',
       format(q.tpl, p.name), q.n, true, 1.00
  FROM pillars p
  CROSS JOIN (VALUES
      (0, 'Where are you strongest on %s today, and what evidence says so?'),
      (1, 'What would have to change for %s to stop being the constraint?')
  ) AS q(n, tpl)
 WHERE p.pipeline_id = pipe
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------- survey ----
INSERT INTO surveys (id, name, description, status, visibility,
                     respondent_email_mode, respondent_name_mode, published_at, created_by)
VALUES (v_survey, 'Mid-programme Reflection (Demo)',
        'The SURVEY task on the demo cohort''s spine.',
        'PUBLISHED', 'PRIVATE', 'NONE', 'NONE', now() - interval '30 days', admin_id)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, description = EXCLUDED.description,
    status = EXCLUDED.status, updated_at = now();

INSERT INTO survey_pillars (id, survey_id, name, description, display_order)
VALUES ('de300000-0201-4000-8000-000000000001', v_survey, 'Halfway',
        'How the programme is landing so far.', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO survey_questions (id, pillar_id, type, prompt_text, display_order, is_required, config_json) VALUES
 ('de300000-0202-4000-8000-000000000001', 'de300000-0201-4000-8000-000000000001',
  'LIKERT',     'How useful has the programme been so far?', 0, true,  '{"min":1,"max":5}'::jsonb),
 ('de300000-0202-4000-8000-000000000002', 'de300000-0201-4000-8000-000000000001',
  'SHORT_TEXT', 'What is the one thing you would change?',   1, false, NULL)
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------ exercise template ----
-- Back-dated 400 days: release-flows.spec.ts picks the NEWEST org provision out
-- of the assign dialog and asserts the "already assigned" badge on it, so this
-- template must never lead a `created_at DESC` list.
INSERT INTO exercise_templates (id, name, description, status, created_by, allow_add_rows, created_at)
VALUES (ex_tpl, 'Map the Competitive Field (Demo)',
        'Name the three competitors you lose to, and what would have to change to stop losing.',
        'PUBLISHED', admin_id, true, now() - interval '400 days')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, description = EXCLUDED.description,
    status = EXCLUDED.status, updated_at = now();

INSERT INTO exercise_columns (id, template_id, name, type, display_order, is_required) VALUES
 ('de300000-0301-4000-8000-000000000001', ex_tpl, 'Competitor',     'TEXT',      0, true),
 ('de300000-0301-4000-8000-000000000002', ex_tpl, 'Where they win', 'LONG_TEXT', 1, true),
 ('de300000-0301-4000-8000-000000000003', ex_tpl, 'Our answer',     'LONG_TEXT', 2, false)
ON CONFLICT (id) DO NOTHING;

-- The org PROVISION row (user_id IS NULL) is what lets an ORG_ADMIN distribute
-- the template at all. Back-dated for the same reason as the template.
-- program_task_id stays NULL here on purpose: a provision is org-level
-- distribution, not one member's cohort work, and V173's tag is per (task,
-- member) — the per-founder rows further down are the ones that carry it.
INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by, created_at)
VALUES ('de300000-0302-4000-8000-000000000001', ex_tpl, org, NULL, admin_id, now() - interval '400 days')
ON CONFLICT (id) DO NOTHING;

-- -------------------------------------------------------------- workshop ----
-- Workshops are org-scoped and standalone. Since V172 no cohort task may point
-- at one, so this workshop exists for the /app/admin/workshops console and the
-- member play flow alone — it is NOT on the cohort's spine. ACTIVE, not DRAFT:
-- a DRAFT workshop is not playable and the demo would dead-end.
INSERT INTO workshops (id, org_id, name, position, status)
VALUES (wshop, org, 'Founder Positioning Sprint (Demo)', 0, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    org_id = EXCLUDED.org_id, name = EXCLUDED.name, status = EXCLUDED.status, updated_at = now();

INSERT INTO workshop_exercises (id, workshop_id, title, position)
VALUES (w_ex, wshop, 'Where does the business actually win?', 0)
ON CONFLICT (id) DO UPDATE SET
    workshop_id = EXCLUDED.workshop_id, title = EXCLUDED.title, updated_at = now();

-- THE CHAIN IS THE POINT, not decoration. workshops-preview.ts resolves each
-- task from the nearest one above it: WEIGHT ← the SORT's left pile, TOP ← the
-- WEIGHT's ranked rows, QUESTION ← the TOP. A QUESTION with no TOP above it is
-- blocked in the builder and traps a member at runtime, so the four rows below
-- are ordered SORT → WEIGHT → TOP → QUESTION and must stay that way.
INSERT INTO workshop_exercise_tasks (id, exercise_id, task_type, assignee, title, position, config) VALUES
 ('de300000-0603-4000-8000-000000000001', w_ex, 'SORT', 'LEAD',
  'Sort the claims: ours to win, or anyone''s?', 0,
  '{"steps": "Deal the cards and agree, as a team, which advantages are genuinely yours.",
    "instructions": "Drag each claim to the side the team can defend with evidence.",
    "leftLabel": "Ours to win", "rightLabel": "Anyone could say this",
    "durationMin": 10,
    "cards": [
      {"id": "c1", "text": "We onboard a new customer in under two days",       "correct": "left"},
      {"id": "c2", "text": "Our founders have run this operation themselves",   "correct": "left"},
      {"id": "c3", "text": "We integrate with the two systems buyers already run", "correct": "left"},
      {"id": "c4", "text": "Our support answers within the hour",               "correct": "left"},
      {"id": "c5", "text": "We are passionate about our customers",             "correct": "right"},
      {"id": "c6", "text": "We use the latest technology",                      "correct": "right"},
      {"id": "c7", "text": "We offer competitive pricing",                      "correct": "right"},
      {"id": "c8", "text": "We are a trusted partner",                          "correct": "right"}
    ]}'::jsonb),
 ('de300000-0603-4000-8000-000000000002', w_ex, 'WEIGHT', 'MEMBER',
  'Score what you kept', 1,
  '{"steps": "Score each surviving claim on how hard it would be for a competitor to copy.",
    "durationMin": 5}'::jsonb),
 ('de300000-0603-4000-8000-000000000003', w_ex, 'TOP', 'LEAD',
  'The three you will lead with', 2,
  '{"steps": "Take the top three and read them out.", "count": 3, "durationMin": 5,
    "celebrate": "That is your positioning — three claims nobody else can make."}'::jsonb),
 ('de300000-0603-4000-8000-000000000004', w_ex, 'QUESTION', 'MEMBER',
  'Write the one-line positioning', 3,
  '{"steps": "Using the top three, write the sentence you would say to a buyer.",
    "prompt": "We help ___ do ___, because we ___.", "durationMin": 5}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
    exercise_id = EXCLUDED.exercise_id, task_type = EXCLUDED.task_type,
    assignee = EXCLUDED.assignee, title = EXCLUDED.title,
    position = EXCLUDED.position, config = EXCLUDED.config, updated_at = now();

-- A member on no team gets "this workshop's teams haven't been set up yet",
-- so the team and its crowned lead are part of the fixture, not a nicety.
INSERT INTO workshop_teams (id, workshop_id, name, position, card)
VALUES (w_team, wshop, 'Team Compass', 0, 'blue')
ON CONFLICT (id) DO UPDATE SET
    workshop_id = EXCLUDED.workshop_id, name = EXCLUDED.name, card = EXCLUDED.card, updated_at = now();

INSERT INTO workshop_team_members (workshop_id, user_id, team_id, is_lead, joined_at) VALUES
 (wshop, fa, w_team, true,  now() - interval '20 days'),
 (wshop, fb, w_team, false, now() - interval '20 days'),
 (wshop, fe, w_team, false, now() - interval '20 days')
ON CONFLICT (workshop_id, user_id) DO UPDATE SET
    team_id = EXCLUDED.team_id, is_lead = EXCLUDED.is_lead;

-- --------------------------------------------------------------- cohorts ----
-- position 10/11 keeps them out of `cohorts[0]`, which two specs read.
-- No org_id since V171: a cohort is authored at the platform level.
--
-- "Demo Founders", NOT "Alpha Founders": e2e-seed.sql already puts an "Alpha
-- Founders" in this same org at position 0, and Playwright's getByRole(name)
-- is a SUBSTRING match, so two cohorts sharing a name made every name-based
-- locator in cohort-view.spec.ts a strict-mode violation. The e2e-seed one
-- owns that name (roi-report.spec.ts and the announcements picker both read
-- the org's first cohort), so this one moved. Keep the two names disjoint —
-- not merely different, since a substring still matches both.
-- No completed_at / COMPLETED status since V183: the lifecycle is DRAFT <->
-- LAUNCHED, and V183 maps the old COMPLETED to LAUNCHED. Delta stays LAUNCHED
-- with an old launched_at — it is only ever asserted on as the cohort Mara's
-- view must NOT show (cohort isolation), so its status is immaterial.
INSERT INTO cohorts (id, name, position, status, launched_at) VALUES
 (cohort_a, 'Demo Founders',      10, 'LAUNCHED', now() - interval '35 days'),
 (cohort_d, 'Delta Alumni (2025)', 11, 'LAUNCHED', now() - interval '260 days')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, position = EXCLUDED.position,
    status = EXCLUDED.status, launched_at = EXCLUDED.launched_at,
    updated_at = now();

-- The org's PARTICIPATION (V171). This is the row every org-scoped read joins
-- through — the cohort header, the roster, announcements, the coach console,
-- the ROI report — so without it both cohorts exist and are invisible to
-- everyone, exactly the way a pipeline with no org provision is.
-- auto_enroll stays false: this file names its five founders itself, and
-- true would sweep in every future member of the org.
INSERT INTO cohort_orgs (cohort_id, org_id, auto_enroll, assigned_at, assigned_by) VALUES
 (cohort_a, org, false, now() - interval '35 days',  admin_id),
 (cohort_d, org, false, now() - interval '260 days', admin_id)
ON CONFLICT (cohort_id, org_id) DO NOTHING;

-- ---------------------------------------------------------------- roster ----
INSERT INTO cohort_members (cohort_id, user_id)
VALUES (cohort_a, fa), (cohort_a, fb), (cohort_a, fc), (cohort_a, fd), (cohort_a, fe)
ON CONFLICT DO NOTHING;

-- Coach 1 covers the whole cohort; D is deliberately handled 1:1 so the board
-- shows both grains.
INSERT INTO coach_assignments (id, org_id, coach_id, cohort_id, member_id, assigned_by, created_at) VALUES
 ('de300000-000c-4000-8000-000000000002', org, v_coach1, cohort_a, NULL, admin_id, now() - interval '34 days'),
 ('de300000-000c-4000-8000-000000000001', org, v_coach2, NULL,     fd,   admin_id, now() - interval '20 days')
ON CONFLICT (id) DO NOTHING;

-- -------------------------------------------------------------- settings ----
INSERT INTO program_settings (cohort_id, stage_label, drip_enabled, due_soon_days,
                              end_label, end_at, baseline_pipeline_id, distance_pipeline_id)
VALUES (cohort_a, 'Sprint', false, 3, 'Demo day', now() + interval '21 days', pipe, pipe)
ON CONFLICT (cohort_id) DO UPDATE SET
    stage_label = 'Sprint', drip_enabled = false, due_soon_days = 3,
    end_label = 'Demo day', end_at = now() + interval '21 days',
    baseline_pipeline_id = pipe, distance_pipeline_id = pipe;

-- --------------------------------------------------------------- modules ----
-- No org_id since V171 either: a module belongs to its cohort, and the cohort
-- carries the org list.
INSERT INTO program_modules (id, cohort_id, name, summary, position, pillar_label, lock_mode, assign_mode)
VALUES
 (m1, cohort_a, 'Find the Vision',
  'Name the destination before you optimise the route. One line, defensible, repeatable.', 0, 'Vision Clarity', 'UNLOCKED', 'ALL'),
 (m2, cohort_a, 'Build the Discipline',
  'Turn intent into a weekly operating rhythm you can actually keep.', 1, 'Discipline', 'UNLOCKED', 'ALL'),
 (m3, cohort_a, 'Fuel the Engine',
  'Protect the energy the plan depends on, and borrow structure from people who have done it.', 2, 'Energy & Motivation', 'UNLOCKED', 'ALL'),
 (m4, cohort_a, 'Focus & Ship',
  'Cut the list to one thing, ship it, and measure the distance travelled.', 3, 'Focus & Flow', 'UNLOCKED', 'ALL')
ON CONFLICT (id) DO UPDATE SET
    cohort_id = EXCLUDED.cohort_id,
    name = EXCLUDED.name, summary = EXCLUDED.summary, position = EXCLUDED.position,
    pillar_label = EXCLUDED.pillar_label, lock_mode = EXCLUDED.lock_mode, updated_at = now();

-- ------------------------------------------------------ typed cohort tasks --
-- V164 allows exactly one BASELINE and one DISTANCE per cohort; fixed ids make
-- that hold by construction rather than by a lookup.
-- The vocabulary is LESSON / COURSE / EXERCISE / ASSESSMENT / SURVEY — V172
-- removed WORKSHOP from ck_program_tasks_task_type, so m4's position 1 is
-- deliberately vacant (V172 renumbers survivors, and the spine elsewhere in
-- this file has never been contiguous).
INSERT INTO program_tasks (id, module_id, name, status, task_type, ref_id, milestone_role, position, due_date)
VALUES
 (t_base,  m1, 'Baseline — Founder Mindset Assessment',  'LIVE', 'ASSESSMENT', pipe,     'BASELINE', 5, NULL),
 (t_check, m2, 'Check-in — Founder Mindset Assessment',  'LIVE', 'ASSESSMENT', pipe,     'CHECKIN',  3, NULL),
 (t_dist,  m4, 'Distance — Founder Mindset Assessment',  'LIVE', 'ASSESSMENT', pipe,     'DISTANCE', 2,
  (now() + interval '18 days')::date),
 (l1,   m1, 'Write your one-line vision',          'LIVE', 'LESSON',   NULL,     NULL, 1, NULL),
 (l2,   m2, 'Design your weekly operating rhythm', 'LIVE', 'LESSON',   NULL,     NULL, 1, (now() - interval '6 days')::date),
 (t_ex, m2, 'Map the competitive field',           'LIVE', 'EXERCISE', ex_tpl,   NULL, 2, NULL),
 (t_co, m3, 'Course: Leadership Essentials',       'LIVE', 'COURSE',   v_course, NULL, 0, NULL),
 (t_su, m3, 'Mid-programme reflection',            'LIVE', 'SURVEY',   v_survey, NULL, 1, NULL),
 (l3,   m4, 'Ship one thing this week',            'LIVE', 'LESSON',   NULL,     NULL, 0, (now() + interval '5 days')::date)
ON CONFLICT (id) DO UPDATE SET
    module_id = EXCLUDED.module_id, name = EXCLUDED.name, status = EXCLUDED.status,
    task_type = EXCLUDED.task_type, ref_id = EXCLUDED.ref_id,
    milestone_role = EXCLUDED.milestone_role, position = EXCLUDED.position,
    due_date = EXCLUDED.due_date, updated_at = now();

-- Lesson form fields (non-LESSON tasks must have none — V164 rule).
INSERT INTO program_task_fields (id, task_id, field_type, required, position, config) VALUES
 ('de300000-0002-4000-8000-000000000001', l1, 'INSTRUCTIONS', false, 0,
  '{"text": "A vision you cannot say in one breath is a vision your team cannot repeat. Write yours, then cut it in half."}'),
 ('de300000-0002-4000-8000-000000000002', l1, 'LONG', true, 1,
  '{"question": "Your one-line vision, and who it is for.", "placeholder": "In 2027, ..."}'),
 ('de300000-0002-4000-8000-000000000003', l2, 'INSTRUCTIONS', false, 0,
  '{"text": "Block the week before the week blocks you. Name the three recurring slots you will defend."}'),
 ('de300000-0002-4000-8000-000000000004', l2, 'LONG', true, 1,
  '{"question": "Describe your weekly operating rhythm and the one slot you will not move.", "placeholder": "Monday ..."}'),
 ('de300000-0002-4000-8000-000000000005', l3, 'INSTRUCTIONS', false, 0,
  '{"text": "Pick the smallest thing that a customer can react to, and ship it before the next session."}'),
 ('de300000-0002-4000-8000-000000000006', l3, 'LONG', true, 1,
  '{"question": "What will you ship this week, and how will you know it landed?", "placeholder": "I will ship ..."}'),
 -- l1 also carries a VIDEO and a required MCQ so one lesson exercises every
 -- interesting field renderer. The url is a real YouTube WATCH url on purpose:
 -- `resolveMedia`/`toEmbedUrl` rewrite it to /embed/, which is the provider
 -- path a bare file url would never reach.
 ('de300000-0002-4000-8000-000000000007', l1, 'VIDEO', false, 2,
  '{"title": "Simon Sinek — Start with why", "url": "https://www.youtube.com/watch?v=u4ZoJKF_VuA"}'),
 ('de300000-0002-4000-8000-000000000008', l1, 'MCQ', true, 3,
  '{"question": "Who is the vision for, first?",
    "options": ["The team who has to repeat it", "Investors", "The website", "Nobody — it is for me"],
    "multi": false}')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------- assessment milestone submissions --
-- Founder A (Mara Reyes):  baseline 58 → check-in 64 → distance 71, COMPARED.
-- Founder B (Samir Iqbal): baseline 52 → distance 58, deliberately NOT compared
--                          (see the comparison block below).
-- C, D and E have none, so the "?" distance node still has subjects.
--
-- The pair is SAME-INSTRUMENT (program_settings points both roles at `pipe`),
-- which is the configuration that crashed ISSUE-1. ComparisonComputeService's
-- `resolveSides` can only tell the two sides apart by their milestone TAG, and
-- refuses a self-comparison, so baseline and distance must be DIFFERENT rows
-- tagged to the BASELINE and DISTANCE tasks respectively — which is exactly
-- what the (uid, task) pairs below produce.
--
-- Every id is md5-derived from (user, task), so a second run is a no-op rather
-- than a second submission.
FOR r IN SELECT * FROM (VALUES
        (fa, t_base,  58.0, 40, 37),
        (fa, t_check, 64.0, 12, 53),
        (fa, t_dist,  71.0,  3, 61),
        (fb, t_base,  52.0, 33, 41),
        (fb, t_dist,  58.0,  5, 29)
    ) AS v(uid, task, score, days, seed)
LOOP
    -- Every (org, pipeline) pair needs a PROVISION row beside its member rows
    -- — the shape V112 established and two partial unique indexes enforce.
    INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by,
                             max_check_ins, created_at, updated_at)
    VALUES (md5('demo:provision:' || org::text || ':' || pipe::text)::uuid, pipe, org, NULL, admin_id, 3,
            now() - interval '50 days', now() - interval '50 days')
    ON CONFLICT (id) DO NOTHING;

    v_assign := md5('demo:assignment:' || org::text || ':' || pipe::text || ':' || r.uid::text)::uuid;
    INSERT INTO assignments (id, pipeline_id, organization_id, user_id, assigned_by,
                             max_check_ins, created_at, updated_at)
    VALUES (v_assign, pipe, org, r.uid, admin_id, 3,
            now() - interval '45 days', now() - interval '45 days')
    ON CONFLICT (id) DO NOTHING;

    v_sub := md5('demo:submission:' || r.uid::text || ':' || r.task::text)::uuid;
    INSERT INTO submissions (id, assignment_id, user_id, status, program_task_id,
                             started_at, submitted_at, evaluated_at, created_at, updated_at)
    VALUES (v_sub, v_assign, r.uid, 'EVALUATED', r.task,
            now() - make_interval(days => r.days + 1), now() - make_interval(days => r.days),
            now() - make_interval(days => r.days), now() - make_interval(days => r.days + 1), now())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO pillar_evaluations (submission_id, pillar_id, score_percentage, maturity_label,
                                    ai_whats_working, ai_what_can_improve, ai_model_used,
                                    evaluated_at, created_at, updated_at)
    SELECT v_sub, p.id, sc.v,
           coalesce((SELECT k FROM jsonb_each(p.maturity_thresholds_json) AS e(k, band)
                      WHERE sc.v >= (band->>0)::int AND sc.v <= (band->>1)::int LIMIT 1), 'Emerging'),
           jsonb_build_array(format('Evidence of deliberate practice in %s.', p.name)),
           jsonb_build_array(format('Make %s visible to the team, not just to yourself.', p.name)),
           'anthropic/claude-sonnet-4',
           now() - make_interval(days => r.days), now() - make_interval(days => r.days), now()
      FROM pillars p
      CROSS JOIN LATERAL (SELECT greatest(9, least(96,
             round(r.score + ((p.display_order * r.seed) % 23) - 11)))::numeric AS v) sc
     WHERE p.pipeline_id = pipe AND p.weight > 0
    ON CONFLICT (submission_id, pillar_id) DO NOTHING;

    INSERT INTO overall_summaries (submission_id, overall_score_percentage, summary_narrative,
                                   strengths, development_areas, core_pattern,
                                   moving_forward_narrative, ai_model_used,
                                   generated_at, created_at, updated_at)
    VALUES (v_sub, r.score,
            CASE WHEN r.days > 20
                 THEN 'A founder with real conviction and an operating system that has not caught up with it yet.'
                 ELSE 'The rhythm is holding. Decisions land faster, and the gap now sits in delegation rather than clarity.' END,
            CASE WHEN r.days > 20
                 THEN '["Direction is specific and evidenced", "Owns outcomes without hedging"]'::jsonb
                 ELSE '["Weekly cadence is now habitual", "Faster feedback loops with customers"]'::jsonb END,
            CASE WHEN r.days > 20
                 THEN '["Weekly cadence collapses under urgent work", "Energy is spent on low-leverage decisions"]'::jsonb
                 ELSE '["Still the single point of decision", "Longer-horizon planning is thin"]'::jsonb END,
            CASE WHEN r.days > 20
                 THEN 'Clear on the destination, improvising the route.'
                 ELSE 'Consistent execution, concentrated ownership.' END,
            'Pick one recurring decision and hand it over with a written rule this sprint.',
            'anthropic/claude-sonnet-4',
            now() - make_interval(days => r.days), now() - make_interval(days => r.days), now())
    ON CONFLICT (submission_id) DO NOTHING;
END LOOP;

-- ------------------------------------------------- the comparison states ----
-- TWO deliberate demo states, not one:
--   Mara Reyes  — both sides evaluated AND the founder_comparisons row present,
--                 so My Growth renders a real before→after and the board's
--                 Distance column shows a delta.
--   Samir Iqbal — both sides evaluated, comparison row ABSENT ON PURPOSE, so
--                 the cohort Settings distance card has a non-zero "not
--                 computed" count and its Recompute button has work to do.
-- Do not "fix" Samir by adding a row; that state is the point.

-- The pair's pillar mapping. ComparisonComputeService auto-seeds this on first
-- use; seeding it here means the row exists before anything reads it. Same
-- instrument on both sides, so every pillar maps to itself. Cohort-scoped
-- since V182 — the mapping is this cohort's Settings config, not global.
INSERT INTO comparison_pillar_mappings (id, cohort_id,
                                        baseline_pipeline_id, distance_pipeline_id,
                                        baseline_pillar_id, distance_pillar_id, source)
SELECT md5('demo:cpm:' || cohort_a::text || ':' || p.id::text)::uuid, cohort_a,
       pipe, pipe, p.id, p.id, 'AUTO'
  FROM pillars p WHERE p.pipeline_id = pipe
ON CONFLICT (id) DO NOTHING;

-- Shift bands: the shipped defaults (ScoringBands.defaultShiftBands) verbatim,
-- so `config_snapshot` and the resolved band match what a real recompute writes.
-- Mara's delta is 71 - 58 = +13 ⇒ band_2 "Moderate".
INSERT INTO founder_comparisons (id, cohort_id, org_id, user_id,
                                 baseline_submission_id, distance_submission_id,
                                 overall_before, overall_after, overall_delta,
                                 overall_band_key, overall_band_label,
                                 config_snapshot, computed_at)
VALUES ('de300000-0700-4000-8000-000000000001', cohort_a, org, fa,
        md5('demo:submission:' || fa::text || ':' || t_base::text)::uuid,
        md5('demo:submission:' || fa::text || ':' || t_dist::text)::uuid,
        58.00, 71.00, 13.00, 'band_2', 'Moderate',
        '[{"key":"decline","label":"Decline","min":null,"max":-1},
          {"key":"band_1","label":"Low","min":0,"max":4},
          {"key":"band_2","label":"Moderate","min":5,"max":14},
          {"key":"band_3","label":"High","min":15,"max":null}]'::jsonb,
        now() - interval '2 days')
ON CONFLICT (cohort_id, user_id) DO NOTHING;

-- Per-pillar rows, read straight off the two submissions' evaluations — the
-- same source `pillarRows` uses. state MAPPED because the pillar is measured on
-- both sides; band per pillar delta.
INSERT INTO founder_comparison_pillars (id, comparison_id, baseline_pillar_id, distance_pillar_id,
                                        pillar_name_snapshot, state, before_pct, after_pct, delta,
                                        band_key, band_label, maturity_before, maturity_after)
SELECT md5('demo:fcp:' || p.id::text)::uuid, 'de300000-0700-4000-8000-000000000001', p.id, p.id,
       p.name, 'MAPPED', b.score_percentage, d.score_percentage,
       d.score_percentage - b.score_percentage,
       CASE WHEN d.score_percentage - b.score_percentage <   0 THEN 'decline'
            WHEN d.score_percentage - b.score_percentage <=  4 THEN 'band_1'
            WHEN d.score_percentage - b.score_percentage <= 14 THEN 'band_2'
            ELSE 'band_3' END,
       CASE WHEN d.score_percentage - b.score_percentage <   0 THEN 'Decline'
            WHEN d.score_percentage - b.score_percentage <=  4 THEN 'Low'
            WHEN d.score_percentage - b.score_percentage <= 14 THEN 'Moderate'
            ELSE 'High' END,
       b.maturity_label, d.maturity_label
  FROM pillars p
  JOIN pillar_evaluations b ON b.pillar_id = p.id
   AND b.submission_id = md5('demo:submission:' || fa::text || ':' || t_base::text)::uuid
  JOIN pillar_evaluations d ON d.pillar_id = p.id
   AND d.submission_id = md5('demo:submission:' || fa::text || ':' || t_dist::text)::uuid
 WHERE p.pipeline_id = pipe
ON CONFLICT (id) DO NOTHING;

-- --------------------------------------------------- lesson work submitted --
INSERT INTO program_submissions (id, task_id, user_id, status, answers, submitted_at, points_awarded, created_at, updated_at)
VALUES
 -- l1 answers carry the MCQ index alongside the long answer: the required-field
 -- invariant below is what would otherwise catch a half-answered submission.
 ('de300000-0008-4000-8000-000000000001', l1, fa, 'SUBMITTED',
  '{"de300000-0002-4000-8000-000000000002": "By 2027 every early-stage founder we serve can see their own readiness in one number and know the next move.",
    "de300000-0002-4000-8000-000000000008": 0}',
  now() - interval '25 days', 55, now() - interval '25 days', now()),
 ('de300000-0008-4000-8000-000000000002', l2, fa, 'SUBMITTED',
  '{"de300000-0002-4000-8000-000000000004": "Monday planning 60m, Wednesday customer calls 90m, Friday review 45m. Friday review never moves."}',
  now() - interval '10 days', 55, now() - interval '10 days', now()),
 ('de300000-0008-4000-8000-000000000003', l1, fb, 'SUBMITTED',
  '{"de300000-0002-4000-8000-000000000002": "We help operations teams stop losing an hour a day to manual reconciliation.",
    "de300000-0002-4000-8000-000000000008": 0}',
  now() - interval '20 days', 55, now() - interval '20 days', now()),
 ('de300000-0008-4000-8000-000000000004', l1, fe, 'SUBMITTED',
  '{"de300000-0002-4000-8000-000000000002": "A place where local makers sell without needing a marketing budget.",
    "de300000-0002-4000-8000-000000000008": 1}',
  now() - interval '18 days', 55, now() - interval '18 days', now())
ON CONFLICT (task_id, user_id) DO UPDATE SET
    status = EXCLUDED.status, answers = EXCLUDED.answers, updated_at = now();

-- ------------------------------------------------------------- exercise -----
-- One founder per interesting review state, so the review console and every
-- status pill have data:
--   Mara Reyes    SUBMITTED with a changes_requested_at stamp ⇒ the
--                 "Resubmitted" pill (a plain SUBMITTED cannot show it) AND a
--                 row awaiting review.
--   Samir Iqbal   CHANGES_REQUESTED — the ball is in the founder's court.
--   Elena Novak   REVIEWED, carrying a quality tag.
--   Akua Osei     IN_PROGRESS — never submitted.
FOR r IN SELECT * FROM (VALUES
        (fa, 'SUBMITTED',         9,  6, NULL,       NULL),
        (fb, 'CHANGES_REQUESTED', 11, 4, NULL,       NULL),
        (fc, 'REVIEWED',          16, NULL::int, 'exemplar', 'Exemplar'),
        (fe, 'IN_PROGRESS',       4,  NULL::int, NULL,       NULL)
    ) AS v(uid, st, days, cr_days, tag_key, tag_label)
LOOP
    v_ea := md5('demo:exercise-assignment:' || org::text || ':' || ex_tpl::text || ':' || r.uid::text)::uuid;
    -- THE TAG IS NOT OPTIONAL (V173). TaskSpineRepository.ensureExerciseSubmission
    -- writes program_task_id when a founder opens the cohort task, and
    -- FounderProfileService.workItems only merges an assignment into its cohort
    -- row when it carries one. Untagged, this work shows as "Direct · org-level"
    -- with no Review link, and cohort-view.spec.ts's exercise test SKIPS instead
    -- of failing. Do NOT drop this column.
    -- DO UPDATE, not DO NOTHING: lanes seeded before this fix already hold the
    -- untagged rows, and a seed that cannot repair the data it owns leaves the
    -- defect on every existing lane (same stance as the Delta answers below).
    -- Known ceiling: if such a lane ALSO holds an app-created row for the same
    -- (task, member) — the founder opened the task in the UI meanwhile —
    -- uq_exercise_assignments_program_task_user rejects the re-tag and the seed
    -- aborts. That lane needs a reset; failing loudly beats leaving the work
    -- unattributed and the spec skipping.
    INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by,
                                      program_task_id, deadline, created_at, updated_at)
    VALUES (v_ea, ex_tpl, org, r.uid, admin_id, t_ex, now() + interval '7 days',
            now() - interval '399 days', now())
    ON CONFLICT (id) DO UPDATE SET
        program_task_id = EXCLUDED.program_task_id, updated_at = now();

    INSERT INTO exercise_submissions (id, assignment_id, user_id, status, last_saved_at, submitted_at,
                                      reviewed_at, changes_requested_at,
                                      quality_tag_key, quality_tag_label, quality_tagged_at, quality_tagged_by,
                                      created_at, updated_at)
    VALUES (md5('demo:exercise-submission:' || v_ea::text)::uuid, v_ea, r.uid, r.st,
            now() - make_interval(days => r.days),
            CASE WHEN r.st IN ('SUBMITTED','REVIEWED') THEN now() - make_interval(days => r.days) END,
            CASE WHEN r.st = 'REVIEWED' THEN now() - make_interval(days => r.days - 2) END,
            CASE WHEN r.cr_days IS NOT NULL THEN now() - make_interval(days => r.cr_days) END,
            r.tag_key, r.tag_label,
            CASE WHEN r.tag_key IS NOT NULL THEN now() - make_interval(days => r.days - 2) END,
            CASE WHEN r.tag_key IS NOT NULL THEN v_coach1 END,
            now() - make_interval(days => r.days + 3), now())
    ON CONFLICT (id) DO UPDATE SET
        status = EXCLUDED.status, submitted_at = EXCLUDED.submitted_at,
        reviewed_at = EXCLUDED.reviewed_at, changes_requested_at = EXCLUDED.changes_requested_at,
        quality_tag_key = EXCLUDED.quality_tag_key, quality_tag_label = EXCLUDED.quality_tag_label,
        quality_tagged_at = EXCLUDED.quality_tagged_at, quality_tagged_by = EXCLUDED.quality_tagged_by,
        updated_at = now();

    -- A submitted exercise with no rows opens empty; one row per founder is
    -- enough for the review console to have something to read.
    INSERT INTO exercise_rows (id, submission_id, display_order, cells, is_starter)
    VALUES (md5('demo:exercise-row:' || v_ea::text)::uuid,
            md5('demo:exercise-submission:' || v_ea::text)::uuid, 0,
            jsonb_build_object(
              'de300000-0301-4000-8000-000000000001', 'Ledgerline',
              'de300000-0301-4000-8000-000000000002', 'Native integrations with the two accounting suites our buyers already run.',
              'de300000-0301-4000-8000-000000000003', 'Ship the reconciliation import before the next renewal window.'),
            false)
    ON CONFLICT (id) DO NOTHING;
END LOOP;

-- --------------------------------------------------- course + survey work ---
INSERT INTO enrollment (id, user_id, course_id, status, progress_pct, enrolled_at) VALUES
 ('de300000-000b-4000-8000-000000000001', fa, v_course, 'ACTIVE', 60, now() - interval '14 days'),
 ('de300000-000b-4000-8000-000000000002', fb, v_course, 'ACTIVE', 45, now() - interval '11 days')
ON CONFLICT (user_id, course_id) DO NOTHING;

-- A SURVEY task has no artifact read of its own: FounderProfileReadRepository
-- .programTasks LEFT JOINs survey_responses ON sr.program_task_id = t.id, so an
-- untagged response leaves the row at "To do" with no View link forever —
-- cohort-view.spec.ts's survey test then skips permanently and the a11y sweep
-- never discovers the viewer's shape. The tag IS the wiring. Do NOT drop it.
--
-- TWO respondents, not one: Mara is DEMO_MEMBER_ID and Elena is
-- DEMO_EXERCISE_MEMBER_ID (web/e2e/_helpers.ts), and they both carry a tagged
-- exercise, so whichever founder a spec picks it finds both viewers. One
-- respondent made the survey green by accident of the choice.
-- DO UPDATE for the same repair reason as the exercise assignments above.
INSERT INTO survey_responses (id, survey_id, source, respondent_user_id, program_task_id,
                              submitted_at, created_at, updated_at) VALUES
 ('de300000-000d-4000-8000-000000000001', v_survey, 'PROGRAM_TASK', fa, t_su,
  now() - interval '8 days', now() - interval '8 days', now()),
 ('de300000-000d-4000-8000-000000000002', v_survey, 'PROGRAM_TASK', fc, t_su,
  now() - interval '5 days', now() - interval '5 days', now())
ON CONFLICT (id) DO UPDATE SET
    program_task_id = EXCLUDED.program_task_id, updated_at = now();

INSERT INTO survey_answers (id, response_id, question_id, response_text, selected_value, numeric_value) VALUES
 ('de300000-000e-4000-8000-000000000001', 'de300000-000d-4000-8000-000000000001',
  'de300000-0202-4000-8000-000000000001', NULL, '4', 4.00),
 ('de300000-000e-4000-8000-000000000002', 'de300000-000d-4000-8000-000000000001',
  'de300000-0202-4000-8000-000000000002', 'More time between the sessions and the deadlines.', NULL, NULL),
 ('de300000-000e-4000-8000-000000000003', 'de300000-000d-4000-8000-000000000002',
  'de300000-0202-4000-8000-000000000001', NULL, '3', 3.00),
 ('de300000-000e-4000-8000-000000000004', 'de300000-000d-4000-8000-000000000002',
  'de300000-0202-4000-8000-000000000002', 'Fewer parallel threads — one module at a time would land better.', NULL, NULL)
ON CONFLICT (id) DO NOTHING;

-- --------------------------------------------------------------- sessions ---
-- Empty expected-attendee set = whole cohort (V163). Only the 1:1 narrows it.
-- No org_id since V171: the cohort alone is the scope.
INSERT INTO sessions (id, cohort_id, type, title, session_date, created_by, created_at, updated_at)
VALUES
 ('de300000-0009-4000-8000-000000000001', cohort_a, 'WORKSHOP', 'Kickoff: vision sprint',
  now() - interval '28 days', admin_id, now() - interval '35 days', now()),
 ('de300000-0009-4000-8000-000000000002', cohort_a, 'WORKSHOP', 'Operating rhythm clinic',
  now() - interval '14 days', admin_id, now() - interval '21 days', now()),
 ('de300000-0009-4000-8000-000000000003', cohort_a, 'COACHING_1ON1', 'Coaching 1:1 — Mara Reyes',
  now() - interval '7 days', v_coach1, now() - interval '10 days', now()),
 ('de300000-0009-4000-8000-000000000004', cohort_a, 'WORKSHOP', 'Demo day rehearsal',
  now() + interval '10 days', admin_id, now() - interval '3 days', now())
ON CONFLICT (id) DO UPDATE SET
    cohort_id = EXCLUDED.cohort_id, title = EXCLUDED.title,
    session_date = EXCLUDED.session_date, updated_at = now();

INSERT INTO session_expected_attendees (session_id, member_id)
VALUES ('de300000-0009-4000-8000-000000000003', fa)
ON CONFLICT DO NOTHING;

INSERT INTO session_attendance (session_id, member_id, marked_at, marked_by) VALUES
 ('de300000-0009-4000-8000-000000000001', fa, now() - interval '28 days', admin_id),
 ('de300000-0009-4000-8000-000000000001', fb, now() - interval '28 days', admin_id),
 ('de300000-0009-4000-8000-000000000001', fd, now() - interval '28 days', admin_id),
 ('de300000-0009-4000-8000-000000000001', fe, now() - interval '28 days', admin_id),
 ('de300000-0009-4000-8000-000000000002', fa, now() - interval '14 days', admin_id),
 ('de300000-0009-4000-8000-000000000002', fb, now() - interval '14 days', admin_id),
 ('de300000-0009-4000-8000-000000000002', fe, now() - interval '14 days', admin_id),
 ('de300000-0009-4000-8000-000000000003', fa, now() - interval '7 days', v_coach1)
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------- announcement ---
INSERT INTO announcements (id, org_id, cohort_id, author_id, body, created_at, updated_at)
VALUES ('de300000-000a-4000-8000-000000000001', org, cohort_a, admin_id,
        'Sprint 3 opens Monday. Bring your one-line vision and the single slot you refused to move last week — we start with those, then rehearse for demo day.',
        now() - interval '4 days', now() - interval '4 days')
ON CONFLICT (id) DO NOTHING;

-- ============================== Delta Alumni (2025) — COMPLETED closing state
INSERT INTO program_settings (cohort_id, stage_label, drip_enabled, due_soon_days, end_label)
VALUES (cohort_d, 'Sprint', false, 3, 'Programme complete')
ON CONFLICT (cohort_id) DO NOTHING;

INSERT INTO cohort_members (cohort_id, user_id) VALUES (cohort_d, fa), (cohort_d, fe)
ON CONFLICT DO NOTHING;

INSERT INTO program_modules (id, cohort_id, name, summary, position, pillar_label, lock_mode, assign_mode)
VALUES
 (dm1, cohort_d, 'Foundations', 'Where the 2025 group started: positioning, and the habit of writing it down.',
  0, 'Vision Clarity', 'UNLOCKED', 'ALL'),
 (dm2, cohort_d, 'Closing the Loop', 'What they shipped, what they kept, and what they handed over.',
  1, 'Focus & Flow', 'UNLOCKED', 'ALL')
ON CONFLICT (id) DO UPDATE SET
    cohort_id = EXCLUDED.cohort_id,
    name = EXCLUDED.name, summary = EXCLUDED.summary, position = EXCLUDED.position,
    pillar_label = EXCLUDED.pillar_label, updated_at = now();

INSERT INTO program_tasks (id, module_id, name, status, task_type, position) VALUES
 ('de300000-0001-4000-8000-000000000011', dm1, 'Position the business in one paragraph', 'LIVE', 'LESSON', 0),
 ('de300000-0001-4000-8000-000000000012', dm1, 'Name your three constraints',            'LIVE', 'LESSON', 1),
 ('de300000-0001-4000-8000-000000000013', dm2, 'Ship the final experiment',              'LIVE', 'LESSON', 0),
 ('de300000-0001-4000-8000-000000000014', dm2, 'Write the handover note',                'LIVE', 'LESSON', 1)
ON CONFLICT (id) DO UPDATE SET
    module_id = EXCLUDED.module_id, name = EXCLUDED.name, status = EXCLUDED.status,
    task_type = EXCLUDED.task_type, position = EXCLUDED.position, updated_at = now();

INSERT INTO program_task_fields (id, task_id, field_type, required, position, config) VALUES
 ('de300000-0002-4000-8000-000000000011', 'de300000-0001-4000-8000-000000000011', 'LONG', true, 0,
  '{"question": "Your positioning, in one paragraph.", "placeholder": "We help ..."}'),
 ('de300000-0002-4000-8000-000000000012', 'de300000-0001-4000-8000-000000000012', 'LONG', true, 0,
  '{"question": "The three constraints you cannot ignore this quarter.", "placeholder": "1. ..."}'),
 ('de300000-0002-4000-8000-000000000013', 'de300000-0001-4000-8000-000000000013', 'INSTRUCTIONS', false, 0,
  '{"text": "Run the last experiment end to end and record what you learned."}'),
 ('de300000-0002-4000-8000-000000000014', 'de300000-0001-4000-8000-000000000014', 'INSTRUCTIONS', false, 0,
  '{"text": "Write the note the next owner needs on day one."}'),
 -- These two tasks used to be instructions-only, so a "Submitted" alumni task
 -- had nothing it could show back. Every LESSON task now has one answerable
 -- field, and every seeded submission answers it (ISSUE-20).
 ('de300000-0002-4000-8000-000000000015', 'de300000-0001-4000-8000-000000000013', 'LONG', true, 1,
  '{"question": "What did the final experiment teach you?", "placeholder": "We ran ..."}'),
 ('de300000-0002-4000-8000-000000000016', 'de300000-0001-4000-8000-000000000014', 'LONG', true, 1,
  '{"question": "The handover note, in short.", "placeholder": "The next owner needs ..."}')
ON CONFLICT (id) DO NOTHING;

-- Every alumni task submitted by every member of the completed cohort.
-- Answers are keyed to the task's OWN program_task_fields ids (inserted just
-- above), never a decorative {"note": …}: a task that reads Done/Submitted on
-- every surface has to open with the founder's words still in the form.
INSERT INTO program_submissions (task_id, user_id, status, answers, submitted_at, points_awarded, created_at, updated_at)
SELECT t.id, cm.user_id, 'SUBMITTED',
       coalesce((SELECT jsonb_object_agg(f.id::text, a.answer)
                   FROM program_task_fields f
                   JOIN (VALUES
                     ('de300000-0002-4000-8000-000000000011'::uuid,
                      'We help independent clinics collect from insurers without hiring a billing team — the owner sees one dashboard, and chases nothing.'),
                     ('de300000-0002-4000-8000-000000000012'::uuid,
                      '1. Two engineers, so one integration a quarter and no more. 2. Cash runway to March, so revenue before headcount. 3. Clinic owners only take calls before 9am.'),
                     ('de300000-0002-4000-8000-000000000015'::uuid,
                      'We ran the self-serve onboarding with eleven clinics. Eight finished it alone, three called us on the insurer step — that step is now a guided form, and activation went from 9 days to 2.'),
                     ('de300000-0002-4000-8000-000000000016'::uuid,
                      'The next owner needs the insurer credential vault (rotated every 90 days), the three clinics still on manual reconciliation, and the standing 8am Thursday call with our first customer.')
                   ) AS a(field_id, answer) ON a.field_id = f.id
                  WHERE f.task_id = t.id), '{}'::jsonb),
       now() - interval '200 days' + (t.position * interval '9 days'), 55,
       now() - interval '210 days', now()
  FROM program_tasks t
  JOIN program_modules m ON m.id = t.module_id
  JOIN cohort_members cm ON cm.cohort_id = m.cohort_id
 WHERE m.cohort_id = cohort_d
-- Not DO NOTHING: lanes seeded before this fix already hold the {"note": …}
-- rows, and a seed that cannot repair the data it owns leaves the defect on
-- every existing lane. Only the answers are rewritten; the submission, its
-- status and its dates stay as they were.
ON CONFLICT (task_id, user_id) DO UPDATE SET
    answers = EXCLUDED.answers, updated_at = now();

END
$seed$;

-- =============================================================================
-- Catalog course content  (ISSUE-10)
-- =============================================================================
-- V77__catalog_seed.sql creates nine demo courses and 70 lessons with
-- body / video_url / asset_url ALL NULL, so every lesson read "nothing here
-- yet" and offered no completion. The fix for that is in the player, not here:
-- `canMarkComplete` keeps a payload-free lesson (and an unbuilt quiz, and an
-- unassigned assessment) completable, so an empty VIDEO / PDF / LINK / QUIZ /
-- SCORM row no longer blocks an enrolment from reaching COMPLETED.
--
-- All this block does is give the PAGE lessons real prose so the demo reads
-- well. It must NEVER touch content_type: V77 is the authority for lesson
-- types, and e2e/catalog.spec.ts asserts that spread (Video, Pdf, Page, Quiz,
-- Link, Scorm, Certification). A lane has no media host, so every other type
-- stays payload-free on purpose.
--
-- Idempotent: fixed md5 ids from V77, same values every run.
UPDATE content c SET body = v.body, updated_at = now()
FROM (VALUES
 (md5('content:leadership-essentials-new-managers:1:2')::uuid,
  '<p>Delegating is not handing over a task, it is handing over an outcome plus the authority to reach it. Say which of the three you are giving: do it my way, decide and tell me, or own it.</p><ul><li>Agree the outcome, the deadline and the check-in — once.</li><li>Never take the task back silently. Ask what is blocked.</li><li>If you would redo the work yourself, you delegated the task and kept the standard.</li></ul>'),
 (md5('content:workplace-safety-compliance-101:0:1')::uuid,
  '<p>Every employee carries three duties, whatever the job title.</p><ul><li>Work to the procedure you were trained on — and say so when you have not been trained.</li><li>Use the protective equipment provided, correctly, every time.</li><li>Report hazards, near misses and incidents the same day.</li></ul>'),
 (md5('content:data-analysis-with-sql:2:1')::uuid,
  '<p>Four shapes cover most analyst work.</p><ul><li>Latest row per group: ROW_NUMBER() in a CTE, then WHERE rn = 1.</li><li>Cohort retention: first-order month as the cohort, activity by month offset.</li><li>Funnel: one CTE per step, LEFT JOIN forward.</li><li>Dense date series: generate the dates, LEFT JOIN the data, so empty days read as zero instead of vanishing.</li></ul>'),
 (md5('content:cybersecurity-awareness:1:1')::uuid,
  '<p>Three questions before you copy anything: what is it, who may see it, and where will it end up.</p><ul><li>Share with named people — never "anyone with the link" for personal data.</li><li>No production data in test systems, spreadsheets or AI tools.</li><li>Delete what you no longer need. Data you do not hold cannot leak.</li></ul>'),
 (md5('content:product-management-foundations:0:2')::uuid,
  '<p>The three of you own the same outcome from different angles. Bring problems and constraints, not solutions dressed up as requirements.</p><ul><li>Involve both at the problem stage; a spec handed over is a spec argued over.</li><li>Say what must be true, not which component to use.</li><li>Disagreement about scope is healthy. Disagreement about the goal means it was never written down.</li></ul>'),
 (md5('content:effective-sales-conversations:1:2')::uuid,
  '<p>Run each scenario twice — once as seller, once as buyer. Ten minutes each, then swap notes.</p><ul><li>Interested, but no budget this quarter.</li><li>The champion loves it; the finance lead has never heard of you.</li><li>They already use a competitor and are mildly unhappy.</li><li>They ask for a discount in the first five minutes.</li></ul>'),
 (md5('content:onboarding-welcome-to-the-team:0:1')::uuid,
  '<p>Get the map of who does what before you need it.</p><ul><li>Book a 20-minute intro with everyone you will work with this month.</li><li>Ask each of them: what do you own, and when should I come to you?</li><li>Note who decides, who reviews, and who to ask when your manager is away.</li></ul>'),
 (md5('content:onboarding-welcome-to-the-team:1:0')::uuid,
  '<p>Work through this once and you will not think about it again.</p><ul><li>Email, chat and calendar — set a photo and your working hours.</li><li>Password manager first; every other account goes into it.</li><li>MFA on everything that offers it.</li><li>Request access as you need it, and say what you need it for.</li></ul>'),
 (md5('content:advanced-excel-for-analysts:1:2')::uuid,
  '<p>Dynamic arrays spill results, LET names intermediate steps, LAMBDA turns a formula you keep pasting into one you can call.</p><ul><li>FILTER, SORT and UNIQUE replace most helper-column gymnastics.</li><li>LET makes long formulas readable and faster — each name is evaluated once.</li><li>Name the LAMBDA in the name manager and the sheet stops being a puzzle.</li></ul>'),
 (md5('content:inclusive-leadership:2:0')::uuid,
  '<p>Write the answers down — a plan you keep in your head is a preference.</p><ul><li>Which of your recent decisions would look different under agreed criteria?</li><li>Who on your team has had the least visible work this quarter?</li><li>Which single meeting practice will you change this month, and how will you know it worked?</li></ul>')
) AS v(id, body)
WHERE c.id = v.id AND c.content_type = 'PAGE';

-- =============================================================================
-- Sanity checks
-- =============================================================================
\set cohort_a '''de300000-0400-4000-8000-000000000001'''
\set cohort_d '''de300000-0400-4000-8000-000000000002'''
\set org      '''2a93054a-f352-4220-a321-a84063924096'''
-- The two founders web/e2e/_helpers.ts names (DEMO_MEMBER_ID /
-- DEMO_EXERCISE_MEMBER_ID) and the two tasks whose artifacts have to be tagged.
\set mara     '''de300000-0501-4000-8000-000000000001'''
\set elena    '''de300000-0501-4000-8000-000000000003'''
\set task_ex  '''de300000-0001-4000-8000-000000000003'''
\set task_su  '''de300000-0001-4000-8000-000000000006'''

\echo '--- INVARIANT: the org is listed on BOTH platform consoles (rows = missing) ---'
-- Without these the seeded cohorts and workshop are invisible on
-- /app/admin/program-flow and /app/admin/workshops.
SELECT s AS org_not_listed_on
  FROM unnest(ARRAY['PROGRAM_FLOW','WORKSHOPS']) AS s
 WHERE NOT EXISTS (SELECT 1 FROM program_surface_orgs pso
                    WHERE pso.org_id = :org AND pso.surface = s);

\echo '--- INVARIANT: both cohorts are ASSIGNED to the org (rows = missing) ---'
-- V171 made a cohort a platform artifact; cohort_orgs is the only thing that
-- says this org participates. Missing here = both cohorts exist and no console
-- in the app can see them.
SELECT c AS cohort_not_assigned_to_org
  FROM unnest(ARRAY[:cohort_a, :cohort_d]::uuid[]) AS c
 WHERE NOT EXISTS (SELECT 1 FROM cohort_orgs co
                    WHERE co.cohort_id = c AND co.org_id = :org);

\echo '--- platform console listings (what each SA console will show) ---'
SELECT pso.surface, p.name || ' → ' || o.name AS organization,
       (SELECT count(*) FROM cohort_orgs co WHERE co.org_id = o.id) AS cohorts,
       (SELECT count(*) FROM workshops w WHERE w.org_id = o.id)     AS workshops
  FROM program_surface_orgs pso
  JOIN organizations o ON o.id = pso.org_id
  LEFT JOIN organizations p ON p.id = o.parent_organization_id
 ORDER BY pso.surface, organization;

\echo '--- cohorts in the target org ---'
SELECT c.name, c.status, c.position,
       (SELECT count(*) FROM cohort_members cm WHERE cm.cohort_id = c.id)   AS founders,
       (SELECT count(*) FROM program_modules m WHERE m.cohort_id = c.id)    AS modules,
       (SELECT count(*) FROM program_tasks t JOIN program_modules m ON m.id = t.module_id
         WHERE m.cohort_id = c.id)                                          AS tasks
  FROM cohorts c
  JOIN cohort_orgs co ON co.cohort_id = c.id AND co.org_id = :org
 ORDER BY c.position, c.name;

\echo '--- counts (Demo Founders) ---'
SELECT 'modules' AS what, count(*) FROM program_modules WHERE cohort_id = :cohort_a
UNION ALL SELECT 'tasks', count(*) FROM program_tasks t JOIN program_modules m ON m.id = t.module_id
  WHERE m.cohort_id = :cohort_a
UNION ALL SELECT 'task_fields', count(*) FROM program_task_fields f JOIN program_tasks t ON t.id = f.task_id
  JOIN program_modules m ON m.id = t.module_id WHERE m.cohort_id = :cohort_a
UNION ALL SELECT 'founders', count(*) FROM cohort_members WHERE cohort_id = :cohort_a
UNION ALL SELECT 'lesson_submissions', count(*) FROM program_submissions ps JOIN program_tasks t ON t.id = ps.task_id
  JOIN program_modules m ON m.id = t.module_id WHERE m.cohort_id = :cohort_a
UNION ALL SELECT 'tagged_assessments', count(*) FROM submissions s JOIN program_tasks t ON t.id = s.program_task_id
  JOIN program_modules m ON m.id = t.module_id WHERE m.cohort_id = :cohort_a
UNION ALL SELECT 'tagged_exercises', count(*) FROM exercise_assignments ea JOIN program_tasks t ON t.id = ea.program_task_id
  JOIN program_modules m ON m.id = t.module_id WHERE m.cohort_id = :cohort_a
UNION ALL SELECT 'tagged_surveys', count(*) FROM survey_responses sr JOIN program_tasks t ON t.id = sr.program_task_id
  JOIN program_modules m ON m.id = t.module_id WHERE m.cohort_id = :cohort_a
UNION ALL SELECT 'sessions', count(*) FROM sessions WHERE cohort_id = :cohort_a
UNION ALL SELECT 'attendance', count(*) FROM session_attendance a JOIN sessions s ON s.id = a.session_id
  WHERE s.cohort_id = :cohort_a
UNION ALL SELECT 'announcements', count(*) FROM announcements WHERE cohort_id = :cohort_a
UNION ALL SELECT 'coaches', count(*) FROM coach_assignments
  WHERE cohort_id = :cohort_a OR member_id IN (SELECT user_id FROM cohort_members WHERE cohort_id = :cohort_a);

\echo '--- tasks by type ---'
SELECT m.cohort_id, t.task_type, t.milestone_role, count(*)
  FROM program_tasks t JOIN program_modules m ON m.id = t.module_id
 WHERE m.cohort_id IN (:cohort_a, :cohort_d)
 GROUP BY 1,2,3 ORDER BY 1,2,3;

\echo '--- INVARIANT: at most one BASELINE and one DISTANCE per cohort ---'
SELECT m.cohort_id, t.milestone_role, count(*) AS n
  FROM program_tasks t JOIN program_modules m ON m.id = t.module_id
 WHERE t.milestone_role IN ('BASELINE','DISTANCE')
 GROUP BY 1,2 HAVING count(*) > 1;

\echo '--- INVARIANT: every non-LESSON task has an existing ref (rows = violations) ---'
SELECT t.id, t.name, t.task_type, t.ref_id
  FROM program_tasks t JOIN program_modules m ON m.id = t.module_id
 WHERE m.cohort_id IN (:cohort_a, :cohort_d)
   AND t.task_type <> 'LESSON'
   AND NOT (CASE t.task_type
        WHEN 'ASSESSMENT' THEN EXISTS (SELECT 1 FROM pipelines p WHERE p.id = t.ref_id AND p.status = 'PUBLISHED')
        WHEN 'EXERCISE'   THEN EXISTS (SELECT 1 FROM exercise_templates e WHERE e.id = t.ref_id)
        WHEN 'COURSE'     THEN EXISTS (SELECT 1 FROM course c WHERE c.id = t.ref_id AND c.state = 'PUBLISHED')
        WHEN 'SURVEY'     THEN EXISTS (SELECT 1 FROM surveys s WHERE s.id = t.ref_id AND s.status = 'PUBLISHED')
        -- No WORKSHOP arm: V172 removed the type from the vocabulary, and its
        -- old check read m.org_id, which V171 dropped. Both are gone together.
        ELSE false END);

\echo '--- INVARIANT: LESSON tasks carry no ref; non-LESSON tasks carry no fields (rows = violations) ---'
SELECT t.id, t.name, t.task_type, t.ref_id, count(f.id) AS fields
  FROM program_tasks t JOIN program_modules m ON m.id = t.module_id
  LEFT JOIN program_task_fields f ON f.task_id = t.id
 WHERE m.cohort_id IN (:cohort_a, :cohort_d)
 GROUP BY t.id, t.name, t.task_type, t.ref_id
HAVING (t.task_type = 'LESSON' AND t.ref_id IS NOT NULL)
    OR (t.task_type <> 'LESSON' AND (t.ref_id IS NULL OR count(f.id) > 0));

\echo '--- INVARIANT: one tagged submission per (user, task) (rows = violations) ---'
SELECT user_id, program_task_id, count(*) FROM submissions
 WHERE program_task_id IS NOT NULL GROUP BY 1,2 HAVING count(*) > 1;

\echo '--- INVARIANT: both named founders carry a TAGGED exercise AND survey (rows = missing) ---'
-- ASSESSMENT is not the only type that rides on program_task_id. V173 gave
-- exercise_assignments and survey_responses the same tag, and it is the ONLY
-- thing that attributes that work to the cohort: FounderProfileService
-- .workItems merges an artifact into its task row only when the tag is present,
-- so an untagged row leaves the Work tab with no View/Review link — and both
-- cohort-view.spec.ts viewer tests SKIP rather than fail, which is how the gap
-- survived. Asserted for BOTH founders the specs name so neither test can pass
-- by accident of whose row it happened to open.
SELECT u.name AS founder, w.artifact AS missing_tagged_work
  FROM users u
  CROSS JOIN (VALUES ('exercise'), ('survey')) AS w(artifact)
 WHERE u.id IN (:mara, :elena)
   AND NOT (CASE w.artifact
        WHEN 'exercise' THEN EXISTS (SELECT 1 FROM exercise_assignments ea
                                      WHERE ea.user_id = u.id AND ea.program_task_id = :task_ex)
        WHEN 'survey'   THEN EXISTS (SELECT 1 FROM survey_responses sr
                                      WHERE sr.respondent_user_id = u.id AND sr.program_task_id = :task_su)
        ELSE false END)
 ORDER BY 1, 2;

\echo '--- INVARIANT: no seeded exercise/survey work left UNTAGGED (rows = violations) ---'
-- The other half of the same rule: a founder of this cohort holding the demo
-- template or the demo survey with a null tag is work the cohort will never
-- show. Scoped to the ids this file owns, so a legitimate direct assignment of
-- some other template is not swept in.
SELECT 'exercise' AS artifact, u.name AS founder, ea.id AS untagged_row
  FROM exercise_assignments ea
  JOIN cohort_members cm ON cm.user_id = ea.user_id AND cm.cohort_id = :cohort_a
  JOIN users u ON u.id = ea.user_id
 WHERE ea.template_id = 'de300000-0300-4000-8000-000000000001'
   AND ea.program_task_id IS NULL
UNION ALL
SELECT 'survey', u.name, sr.id
  FROM survey_responses sr
  JOIN cohort_members cm ON cm.user_id = sr.respondent_user_id AND cm.cohort_id = :cohort_a
  JOIN users u ON u.id = sr.respondent_user_id
 WHERE sr.survey_id = 'de300000-0200-4000-8000-000000000001'
   AND sr.source = 'PROGRAM_TASK'
   AND sr.program_task_id IS NULL;

\echo '--- INVARIANT: seeded rows all carry created_at/updated_at (rows = violations) ---'
SELECT 'program_modules' AS t, count(*) FROM program_modules WHERE created_at IS NULL OR updated_at IS NULL
UNION ALL SELECT 'program_tasks', count(*) FROM program_tasks WHERE created_at IS NULL OR updated_at IS NULL
UNION ALL SELECT 'program_submissions', count(*) FROM program_submissions WHERE created_at IS NULL OR updated_at IS NULL
UNION ALL SELECT 'submissions', count(*) FROM submissions WHERE created_at IS NULL OR updated_at IS NULL
UNION ALL SELECT 'pillar_evaluations', count(*) FROM pillar_evaluations WHERE created_at IS NULL OR updated_at IS NULL
UNION ALL SELECT 'overall_summaries', count(*) FROM overall_summaries WHERE created_at IS NULL OR updated_at IS NULL
UNION ALL SELECT 'sessions', count(*) FROM sessions WHERE created_at IS NULL OR updated_at IS NULL
UNION ALL SELECT 'announcements', count(*) FROM announcements WHERE created_at IS NULL OR updated_at IS NULL;

\echo '--- INVARIANT: every seeded answer key is a real field of its task (rows = violations) ---'
-- The ISSUE-20 defect in one query: a submission that reads Done/Submitted but
-- whose answers are keyed to something the form has never heard of opens empty.
SELECT ps.task_id, ps.user_id, k AS stray_answer_key
  FROM program_submissions ps
  JOIN program_tasks t ON t.id = ps.task_id
  JOIN program_modules m ON m.id = t.module_id
  CROSS JOIN LATERAL jsonb_object_keys(ps.answers) AS k
 WHERE m.cohort_id IN (:cohort_a, :cohort_d)
   AND NOT EXISTS (SELECT 1 FROM program_task_fields f
                    WHERE f.task_id = ps.task_id AND f.id::text = k);

\echo '--- INVARIANT: every SUBMITTED submission answers every answerable field (rows = violations) ---'
-- "Answerable" is the app's own list (program-flow-types.ts ANSWERABLE_TYPES),
-- not "anything that is not INSTRUCTIONS": VIDEO is presentational too, and
-- spelling that as `<> 'INSTRUCTIONS'` flagged a correct submission the moment
-- l1 gained a video.
SELECT ps.task_id, ps.user_id, f.id AS unanswered_field
  FROM program_submissions ps
  JOIN program_tasks t ON t.id = ps.task_id
  JOIN program_modules m ON m.id = t.module_id
  JOIN program_task_fields f ON f.task_id = ps.task_id
   AND f.field_type IN ('MCQ','SHORT','LONG','FILE','CHECKLIST','RATING')
 WHERE m.cohort_id IN (:cohort_a, :cohort_d)
   AND ps.status = 'SUBMITTED'
   AND NOT (ps.answers ? f.id::text);

\echo '--- INVARIANT: every catalog PAGE lesson has prose (rows = violations) ---'
-- Only PAGE is asserted. A contentless VIDEO / PDF / LINK / QUIZ / SCORM lesson
-- is legitimate demo data — the player keeps it completable — and rewriting it
-- into a PAGE is what broke the lesson-type spread once already.
SELECT c.slug, ct.title, ct.content_type
  FROM content ct
  JOIN section s ON s.id = ct.section_id
  JOIN course c ON c.id = s.course_id
 WHERE c.org_id = '00000000-0000-0000-0000-0000000000ca'
   AND ct.content_type = 'PAGE'
   AND (ct.body IS NULL OR ct.body = '');

\echo '--- catalog lesson types (must stay a spread, not all PAGE) ---'
SELECT ct.content_type, count(*)
  FROM content ct
  JOIN section s ON s.id = ct.section_id
  JOIN course c ON c.id = s.course_id
 WHERE c.org_id = '00000000-0000-0000-0000-0000000000ca'
 GROUP BY 1 ORDER BY 1;

\echo '--- INVARIANT: all five task types LIVE on Demo Founders (rows = missing) ---'
-- Five, not six: V172 removed WORKSHOP from ck_program_tasks_task_type.
SELECT t AS missing_task_type
  FROM unnest(ARRAY['LESSON','COURSE','EXERCISE','ASSESSMENT','SURVEY']) AS t
 WHERE NOT EXISTS (SELECT 1 FROM program_tasks pt JOIN program_modules m ON m.id = pt.module_id
                    WHERE m.cohort_id = :cohort_a AND pt.task_type = t AND pt.status = 'LIVE');

\echo '--- workshop chain (SORT -> WEIGHT -> TOP -> QUESTION, in that order) ---'
SELECT w.name AS workshop, w.status, wt.position, wt.task_type, wt.assignee, wt.title
  FROM workshops w
  JOIN workshop_exercises we ON we.workshop_id = w.id
  JOIN workshop_exercise_tasks wt ON wt.exercise_id = we.id
 WHERE w.org_id = :org ORDER BY wt.position;

\echo '--- workshop team (a lead must be crowned, or the member sees "not set up") ---'
SELECT tm.name AS team, u.name AS member, wm.is_lead
  FROM workshop_team_members wm
  JOIN workshop_teams tm ON tm.id = wm.team_id
  JOIN users u ON u.id = wm.user_id
 WHERE wm.workshop_id = 'de300000-0600-4000-8000-000000000001'
 ORDER BY wm.is_lead DESC, u.name;

\echo '--- per-founder progress + comparison state (Demo Founders) ---'
SELECT u.name AS founder,
       (SELECT os.overall_score_percentage FROM submissions s
          JOIN overall_summaries os ON os.submission_id = s.id
          JOIN program_tasks pt ON pt.id = s.program_task_id
         WHERE s.user_id = u.id AND pt.milestone_role = 'BASELINE') AS baseline,
       (SELECT os.overall_score_percentage FROM submissions s
          JOIN overall_summaries os ON os.submission_id = s.id
          JOIN program_tasks pt ON pt.id = s.program_task_id
         WHERE s.user_id = u.id AND pt.milestone_role = 'DISTANCE') AS distance,
       CASE WHEN EXISTS (SELECT 1 FROM founder_comparisons fc
                          WHERE fc.cohort_id = :cohort_a AND fc.user_id = u.id)
            THEN 'COMPUTED'
            WHEN EXISTS (SELECT 1 FROM submissions s JOIN program_tasks pt ON pt.id = s.program_task_id
                          WHERE s.user_id = u.id AND pt.milestone_role = 'DISTANCE' AND s.status = 'EVALUATED')
            THEN 'READY, NOT COMPUTED'
            ELSE 'not ready' END AS comparison,
       (SELECT es.status || coalesce(' (resubmitted)', '') FROM exercise_submissions es
          JOIN exercise_assignments ea ON ea.id = es.assignment_id
         WHERE ea.user_id = u.id AND ea.organization_id = :org
           AND es.status = 'SUBMITTED' AND es.changes_requested_at IS NOT NULL) AS resubmitted,
       (SELECT es.status FROM exercise_submissions es
          JOIN exercise_assignments ea ON ea.id = es.assignment_id
         WHERE ea.user_id = u.id AND ea.organization_id = :org
           AND ea.template_id = 'de300000-0300-4000-8000-000000000001') AS exercise_state,
       (SELECT es.quality_tag_label FROM exercise_submissions es
          JOIN exercise_assignments ea ON ea.id = es.assignment_id
         WHERE ea.user_id = u.id AND ea.template_id = 'de300000-0300-4000-8000-000000000001') AS quality_tag
  FROM users u JOIN cohort_members cm ON cm.user_id = u.id
 WHERE cm.cohort_id = :cohort_a ORDER BY u.name;

\echo '--- the computed comparison (Mara Reyes) ---'
SELECT fc.overall_before, fc.overall_after, fc.overall_delta, fc.overall_band_label,
       (SELECT count(*) FROM founder_comparison_pillars p WHERE p.comparison_id = fc.id) AS pillar_rows
  FROM founder_comparisons fc WHERE fc.cohort_id = :cohort_a;

\echo '--- INVARIANT: comparison sides must be DIFFERENT rows (rows = violations) ---'
SELECT id, user_id FROM founder_comparisons WHERE baseline_submission_id = distance_submission_id;

\echo '--- founder A trajectory (baseline -> check-in -> distance) ---'
SELECT t.milestone_role, s.status, os.overall_score_percentage AS fri,
       (SELECT count(*) FROM pillar_evaluations pe WHERE pe.submission_id = s.id) AS pillars,
       to_char(s.evaluated_at, 'YYYY-MM-DD') AS evaluated
  FROM submissions s
  JOIN program_tasks t ON t.id = s.program_task_id
  LEFT JOIN overall_summaries os ON os.submission_id = s.id
 WHERE s.user_id = 'de300000-0501-4000-8000-000000000001'
 ORDER BY s.evaluated_at;
