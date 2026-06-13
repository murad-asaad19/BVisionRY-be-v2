-- =============================================================================
-- V77__catalog_seed.sql — Bvisionry Academy catalog seed
-- =============================================================================
-- Seeds a dedicated 'Bvisionry Academy' organisation, nine instructor users,
-- and nine published demo courses (each with sections, content/lessons, tags,
-- course_tag links, learning outcomes and reviews).
--
-- All primary keys are FIXED literals so the seed is fully idempotent — every
-- INSERT is guarded with ON CONFLICT DO NOTHING. Re-running this migration
-- against a partially-seeded database is safe.
--
-- Academy org UUID:  00000000-0000-0000-0000-0000000000ca  ('ca' = catalog)
-- Instructor UUIDs:  00000000-0000-0000-0001-{00n}         (n = 01..09)
-- Course UUIDs:      md5('course:<slug>')::uuid             (deterministic)
-- Other entity UUIDs: md5('<type>:<key>')::uuid             (deterministic)
-- =============================================================================

-- =============================================================================
-- 1. Academy organization
-- =============================================================================
INSERT INTO organizations (id, name, description, subscription_tier, is_active)
VALUES (
    '00000000-0000-0000-0000-0000000000ca',
    'Bvisionry Academy',
    'Official Bvisionry Learn course catalog — public courses for individuals and teams.',
    'FREE',
    true
)
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- 2. Instructor users (role = INSTRUCTOR, status = ACTIVE)
-- password_hash NULL — instructors do not log in via the catalog service.
-- =============================================================================
INSERT INTO users (id, email, name, password_hash, role, organization_id, status)
VALUES
  ('00000000-0000-0000-0001-000000000001', 'amara@bvisionry-academy.com',
   'Dr. Amara Okafor',   NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000002', 'marcus@bvisionry-academy.com',
   'Marcus Reyes',       NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000003', 'lena@bvisionry-academy.com',
   'Lena Petrova',       NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000004', 'samir@bvisionry-academy.com',
   'Samir Haddad',       NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000005', 'nora@bvisionry-academy.com',
   'Nora Bianchi',       NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000006', 'diego@bvisionry-academy.com',
   'Diego Fuentes',      NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000007', 'aiko@bvisionry-academy.com',
   'Aiko Tanaka',        NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000008', 'grace@bvisionry-academy.com',
   'Grace Whitman',      NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE'),
  ('00000000-0000-0000-0001-000000000009', 'kwame@bvisionry-academy.com',
   'Kwame Mensah',       NULL, 'INSTRUCTOR', '00000000-0000-0000-0000-0000000000ca', 'ACTIVE')
ON CONFLICT (email) DO NOTHING;

-- =============================================================================
-- 3. Courses (9 published courses)
-- instructor_name / instructor_title / instructor_bio are denormalized from the
-- users rows above so the public catalog list needs no join to the users table.
-- =============================================================================
INSERT INTO course (
    id, org_id, slug, title, subtitle, category,
    level, mode, audience, access,
    description, price, currency,
    rating, reviews_count, learners_count,
    duration_hours, lessons_count,
    instructor_id, instructor_name, instructor_title, instructor_bio,
    enroll_policy, visibility, cover_gradient, state,
    certification_title, certification_passing_pct)
VALUES

  -- 1 Leadership Essentials
  (md5('course:leadership-essentials-new-managers')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'leadership-essentials-new-managers',
   'Leadership Essentials for New Managers',
   'Lead with confidence from your very first week.',
   'Leadership', 'INTERMEDIATE', 'INSTRUCTOR_LED', 'PUBLIC', 'EVERYONE',
   'Stepping into management is a craft you can learn. This course gives new managers a practical toolkit for one-on-ones, feedback, delegation and building trust — so you stop doing everyone''s job and start multiplying your team.',
   149, 'USD', 4.8, 238, 5120, 6.50, 9,
   '00000000-0000-0000-0001-000000000001',
   'Dr. Amara Okafor',
   'Leadership Coach & Former VP of People',
   'Amara has coached 500+ first-time managers and led people functions at two high-growth scale-ups. She blends behavioural science with hands-on playbooks.',
   'OPEN', 'PUBLIC',
   'linear-gradient(135deg, #0b1f3a 0%, #1d4ed8 55%, #f59e0b 100%)',
   'PUBLISHED', 'Certified New Manager', 80),

  -- 2 Workplace Safety
  (md5('course:workplace-safety-compliance-101')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'workplace-safety-compliance-101',
   'Workplace Safety & Compliance 101',
   'The essentials every employee needs, minus the jargon.',
   'Compliance', 'BEGINNER', 'SELF_PACED', 'EMPLOYEE', 'SIGNED_IN',
   'A clear, no-nonsense walkthrough of the workplace safety and compliance basics: hazard awareness, incident reporting, and your responsibilities. Built to be completed in an afternoon.',
   NULL, 'USD', 4.5, 410, 9870, 2.50, 7,
   '00000000-0000-0000-0001-000000000002',
   'Marcus Reyes',
   'Workplace Safety & Compliance Lead',
   'Marcus is a certified compliance officer who has built safety programmes for distributed teams across manufacturing and tech.',
   'OPEN', 'MEMBERS',
   'linear-gradient(135deg, #0b1f3a 0%, #0e7490 60%, #1d4ed8 100%)',
   'PUBLISHED', 'Workplace Safety Compliance', 75),

  -- 3 Data Analysis with SQL
  (md5('course:data-analysis-with-sql')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'data-analysis-with-sql',
   'Data Analysis with SQL',
   'Go from SELECT * to real analytical insight.',
   'Data & AI', 'INTERMEDIATE', 'SELF_PACED', 'PUBLIC', 'EVERYONE',
   'Learn to answer real business questions with SQL. We cover joins, aggregation, window functions and query patterns analysts actually use, working with realistic datasets throughout.',
   99, 'USD', 4.7, 326, 7340, 8.00, 9,
   '00000000-0000-0000-0001-000000000003',
   'Lena Petrova',
   'Senior Data Analyst',
   'Lena turns messy data into decisions. She has taught SQL and analytics to thousands of analysts and product folks.',
   'PAYMENT', 'PUBLIC',
   'linear-gradient(135deg, #0b1f3a 0%, #2563eb 60%, #f59e0b 100%)',
   'PUBLISHED', 'SQL Data Analyst', 80),

  -- 4 Cybersecurity Awareness
  (md5('course:cybersecurity-awareness')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'cybersecurity-awareness',
   'Cybersecurity Awareness',
   'Spot phishing, protect data, and stay out of the headlines.',
   'Compliance', 'BEGINNER', 'SELF_PACED', 'EMPLOYEE', 'SIGNED_IN',
   'Most breaches start with a person, not a firewall. This course builds the everyday habits that keep you and your organisation safe — passwords, phishing, devices and data handling.',
   NULL, 'USD', 4.6, 512, 12450, 3.00, 7,
   '00000000-0000-0000-0001-000000000004',
   'Samir Haddad',
   'Head of Information Security',
   'Samir runs security awareness for a 4,000-person workforce and believes good security is mostly good habits.',
   'OPEN', 'MEMBERS',
   'linear-gradient(135deg, #0b1f3a 0%, #1e3a8a 55%, #0e7490 100%)',
   'PUBLISHED', 'Security Awareness Certified', 80),

  -- 5 Product Management Foundations
  (md5('course:product-management-foundations')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'product-management-foundations',
   'Product Management Foundations',
   'From idea to roadmap to shipped — the PM core loop.',
   'Product', 'BEGINNER', 'INSTRUCTOR_LED', 'PUBLIC', 'EVERYONE',
   'A grounded introduction to product management: customer discovery, prioritisation, writing crisp specs, and working with engineering and design to ship things that matter.',
   129, 'USD', 4.6, 198, 4360, 7.50, 8,
   '00000000-0000-0000-0001-000000000005',
   'Nora Bianchi',
   'Director of Product',
   'Nora has shipped products from 0 to 1 and 1 to scale. She mentors PMs on discovery, strategy and delivery.',
   'PAYMENT', 'PUBLIC',
   'linear-gradient(135deg, #0b1f3a 0%, #4338ca 55%, #f59e0b 100%)',
   'PUBLISHED', NULL, NULL),

  -- 6 Effective Sales Conversations
  (md5('course:effective-sales-conversations')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'effective-sales-conversations',
   'Effective Sales Conversations',
   'Earn trust, ask better questions, close more.',
   'Sales', 'INTERMEDIATE', 'BLENDED', 'PUBLIC', 'EVERYONE',
   'Selling is helping at scale. Learn the consultative conversation framework top reps use to uncover needs, handle objections, and move deals forward without being pushy.',
   89, 'USD', 4.7, 274, 6120, 5.50, 8,
   '00000000-0000-0000-0001-000000000006',
   'Diego Fuentes',
   'Sales Enablement Coach',
   'Diego has closed eight figures in B2B deals and now coaches reps on consultative selling.',
   'PAYMENT', 'PUBLIC',
   'linear-gradient(135deg, #0b1f3a 0%, #1d4ed8 50%, #fbbf24 100%)',
   'PUBLISHED', NULL, NULL),

  -- 7 Onboarding
  (md5('course:onboarding-welcome-to-the-team')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'onboarding-welcome-to-the-team',
   'Onboarding: Welcome to the Team',
   'Everything you need to feel at home in your first 30 days.',
   'Onboarding', 'BEGINNER', 'SELF_PACED', 'EMPLOYEE', 'SIGNED_IN',
   'A warm, practical onboarding path for new hires: how we work, where to find things, who''s who, and the tools and norms that will make your first month a success.',
   NULL, 'USD', 4.9, 156, 3210, 2.00, 6,
   '00000000-0000-0000-0001-000000000007',
   'Aiko Tanaka',
   'People & Culture Partner',
   'Aiko designs onboarding experiences that make new hires productive and welcome from week one.',
   'OPEN', 'MEMBERS',
   'linear-gradient(135deg, #0b1f3a 0%, #0e7490 50%, #fbbf24 100%)',
   'PUBLISHED', NULL, NULL),

  -- 8 Advanced Excel for Analysts
  (md5('course:advanced-excel-for-analysts')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'advanced-excel-for-analysts',
   'Advanced Excel for Analysts',
   'Power Query, pivots and models that scale.',
   'Data & AI', 'ADVANCED', 'SELF_PACED', 'PUBLIC', 'EVERYONE',
   'Push Excel far beyond formulas. Master Power Query, advanced PivotTables, dynamic arrays and dashboard techniques to build models analysts can trust and reuse.',
   79, 'USD', 4.6, 221, 5890, 6.00, 9,
   '00000000-0000-0000-0001-000000000008',
   'Grace Whitman',
   'Analytics Trainer',
   'Grace has spent a decade making spreadsheets do extraordinary things and teaching others to do the same.',
   'PAYMENT', 'PUBLIC',
   'linear-gradient(135deg, #0b1f3a 0%, #2563eb 55%, #f59e0b 100%)',
   'PUBLISHED', 'Advanced Excel Analyst', 85),

  -- 9 Inclusive Leadership
  (md5('course:inclusive-leadership')::uuid,
   '00000000-0000-0000-0000-0000000000ca',
   'inclusive-leadership',
   'Inclusive Leadership',
   'Build teams where everyone does their best work.',
   'Leadership', 'INTERMEDIATE', 'COHORT', 'B2B', 'ENROLLED',
   'Inclusive leadership is a competitive advantage. This cohort-based programme helps leaders recognise bias, design fair processes, and create the psychological safety high-performing teams depend on.',
   NULL, 'USD', 4.8, 142, 2480, 5.00, 7,
   '00000000-0000-0000-0001-000000000009',
   'Kwame Mensah',
   'Inclusive Leadership Facilitator',
   'Kwame partners with leadership teams to build cultures where everyone can do their best work.',
   'INVITATION', 'MEMBERS',
   'linear-gradient(135deg, #0b1f3a 0%, #4338ca 50%, #fbbf24 100%)',
   'PUBLISHED', 'Inclusive Leadership Certificate', 80)

ON CONFLICT (slug) DO NOTHING;

-- =============================================================================
-- 4. Tags
-- =============================================================================
INSERT INTO tag (id, org_id, name)
VALUES
  (md5('tag:leadership')::uuid,    '00000000-0000-0000-0000-0000000000ca', 'leadership'),
  (md5('tag:management')::uuid,    '00000000-0000-0000-0000-0000000000ca', 'management'),
  (md5('tag:communication')::uuid, '00000000-0000-0000-0000-0000000000ca', 'communication'),
  (md5('tag:teams')::uuid,         '00000000-0000-0000-0000-0000000000ca', 'teams'),
  (md5('tag:compliance')::uuid,    '00000000-0000-0000-0000-0000000000ca', 'compliance'),
  (md5('tag:safety')::uuid,        '00000000-0000-0000-0000-0000000000ca', 'safety'),
  (md5('tag:onboarding')::uuid,    '00000000-0000-0000-0000-0000000000ca', 'onboarding'),
  (md5('tag:security')::uuid,      '00000000-0000-0000-0000-0000000000ca', 'security'),
  (md5('tag:privacy')::uuid,       '00000000-0000-0000-0000-0000000000ca', 'privacy'),
  (md5('tag:sql')::uuid,           '00000000-0000-0000-0000-0000000000ca', 'sql'),
  (md5('tag:data')::uuid,          '00000000-0000-0000-0000-0000000000ca', 'data'),
  (md5('tag:analytics')::uuid,     '00000000-0000-0000-0000-0000000000ca', 'analytics'),
  (md5('tag:databases')::uuid,     '00000000-0000-0000-0000-0000000000ca', 'databases'),
  (md5('tag:excel')::uuid,         '00000000-0000-0000-0000-0000000000ca', 'excel'),
  (md5('tag:spreadsheets')::uuid,  '00000000-0000-0000-0000-0000000000ca', 'spreadsheets'),
  (md5('tag:product')::uuid,       '00000000-0000-0000-0000-0000000000ca', 'product'),
  (md5('tag:strategy')::uuid,      '00000000-0000-0000-0000-0000000000ca', 'strategy'),
  (md5('tag:discovery')::uuid,     '00000000-0000-0000-0000-0000000000ca', 'discovery'),
  (md5('tag:sales')::uuid,         '00000000-0000-0000-0000-0000000000ca', 'sales'),
  (md5('tag:negotiation')::uuid,   '00000000-0000-0000-0000-0000000000ca', 'negotiation'),
  (md5('tag:culture')::uuid,       '00000000-0000-0000-0000-0000000000ca', 'culture'),
  (md5('tag:dei')::uuid,           '00000000-0000-0000-0000-0000000000ca', 'dei'),
  (md5('tag:hr')::uuid,            '00000000-0000-0000-0000-0000000000ca', 'hr')
ON CONFLICT (org_id, name) DO NOTHING;

-- =============================================================================
-- 5. Course-tag links
-- =============================================================================
INSERT INTO course_tag (course_id, tag_id)
VALUES
  -- 1 leadership-essentials
  (md5('course:leadership-essentials-new-managers')::uuid, md5('tag:leadership')::uuid),
  (md5('course:leadership-essentials-new-managers')::uuid, md5('tag:management')::uuid),
  (md5('course:leadership-essentials-new-managers')::uuid, md5('tag:communication')::uuid),
  (md5('course:leadership-essentials-new-managers')::uuid, md5('tag:teams')::uuid),
  -- 2 workplace-safety
  (md5('course:workplace-safety-compliance-101')::uuid, md5('tag:compliance')::uuid),
  (md5('course:workplace-safety-compliance-101')::uuid, md5('tag:safety')::uuid),
  (md5('course:workplace-safety-compliance-101')::uuid, md5('tag:hr')::uuid),
  -- 3 data-analysis-with-sql
  (md5('course:data-analysis-with-sql')::uuid, md5('tag:sql')::uuid),
  (md5('course:data-analysis-with-sql')::uuid, md5('tag:data')::uuid),
  (md5('course:data-analysis-with-sql')::uuid, md5('tag:analytics')::uuid),
  (md5('course:data-analysis-with-sql')::uuid, md5('tag:databases')::uuid),
  -- 4 cybersecurity-awareness
  (md5('course:cybersecurity-awareness')::uuid, md5('tag:security')::uuid),
  (md5('course:cybersecurity-awareness')::uuid, md5('tag:compliance')::uuid),
  (md5('course:cybersecurity-awareness')::uuid, md5('tag:privacy')::uuid),
  -- 5 product-management-foundations
  (md5('course:product-management-foundations')::uuid, md5('tag:product')::uuid),
  (md5('course:product-management-foundations')::uuid, md5('tag:strategy')::uuid),
  (md5('course:product-management-foundations')::uuid, md5('tag:discovery')::uuid),
  -- 6 effective-sales-conversations
  (md5('course:effective-sales-conversations')::uuid, md5('tag:sales')::uuid),
  (md5('course:effective-sales-conversations')::uuid, md5('tag:communication')::uuid),
  (md5('course:effective-sales-conversations')::uuid, md5('tag:negotiation')::uuid),
  -- 7 onboarding
  (md5('course:onboarding-welcome-to-the-team')::uuid, md5('tag:onboarding')::uuid),
  (md5('course:onboarding-welcome-to-the-team')::uuid, md5('tag:culture')::uuid),
  (md5('course:onboarding-welcome-to-the-team')::uuid, md5('tag:hr')::uuid),
  -- 8 advanced-excel
  (md5('course:advanced-excel-for-analysts')::uuid, md5('tag:excel')::uuid),
  (md5('course:advanced-excel-for-analysts')::uuid, md5('tag:data')::uuid),
  (md5('course:advanced-excel-for-analysts')::uuid, md5('tag:analytics')::uuid),
  (md5('course:advanced-excel-for-analysts')::uuid, md5('tag:spreadsheets')::uuid),
  -- 9 inclusive-leadership
  (md5('course:inclusive-leadership')::uuid, md5('tag:leadership')::uuid),
  (md5('course:inclusive-leadership')::uuid, md5('tag:dei')::uuid),
  (md5('course:inclusive-leadership')::uuid, md5('tag:culture')::uuid),
  (md5('course:inclusive-leadership')::uuid, md5('tag:teams')::uuid)
ON CONFLICT (course_id, tag_id) DO NOTHING;

-- =============================================================================
-- 6. Learning outcomes
-- =============================================================================
INSERT INTO course_outcome (course_id, sequence, outcome)
VALUES
  -- 1 leadership-essentials
  (md5('course:leadership-essentials-new-managers')::uuid, 0, 'Run effective one-on-ones that build trust and momentum'),
  (md5('course:leadership-essentials-new-managers')::uuid, 1, 'Give clear, kind feedback that actually changes behaviour'),
  (md5('course:leadership-essentials-new-managers')::uuid, 2, 'Delegate work without losing visibility or quality'),
  (md5('course:leadership-essentials-new-managers')::uuid, 3, 'Set goals and expectations your team understands'),
  (md5('course:leadership-essentials-new-managers')::uuid, 4, 'Handle your first difficult conversations with confidence'),
  -- 2 workplace-safety
  (md5('course:workplace-safety-compliance-101')::uuid, 0, 'Identify common workplace hazards before they cause harm'),
  (md5('course:workplace-safety-compliance-101')::uuid, 1, 'Follow correct incident reporting procedures'),
  (md5('course:workplace-safety-compliance-101')::uuid, 2, 'Understand your individual compliance responsibilities'),
  (md5('course:workplace-safety-compliance-101')::uuid, 3, 'Respond appropriately in an emergency'),
  -- 3 data-analysis-with-sql
  (md5('course:data-analysis-with-sql')::uuid, 0, 'Write SELECT, JOIN and GROUP BY queries with confidence'),
  (md5('course:data-analysis-with-sql')::uuid, 1, 'Use window functions for running totals and rankings'),
  (md5('course:data-analysis-with-sql')::uuid, 2, 'Translate business questions into SQL'),
  (md5('course:data-analysis-with-sql')::uuid, 3, 'Clean and shape messy data for analysis'),
  (md5('course:data-analysis-with-sql')::uuid, 4, 'Build reusable, readable analytical queries'),
  -- 4 cybersecurity-awareness
  (md5('course:cybersecurity-awareness')::uuid, 0, 'Recognise phishing and social-engineering attempts'),
  (md5('course:cybersecurity-awareness')::uuid, 1, 'Create and manage strong, unique passwords'),
  (md5('course:cybersecurity-awareness')::uuid, 2, 'Handle sensitive data safely day to day'),
  (md5('course:cybersecurity-awareness')::uuid, 3, 'Keep your devices and accounts secure'),
  -- 5 product-management-foundations
  (md5('course:product-management-foundations')::uuid, 0, 'Run customer discovery that surfaces real needs'),
  (md5('course:product-management-foundations')::uuid, 1, 'Prioritise ruthlessly with simple frameworks'),
  (md5('course:product-management-foundations')::uuid, 2, 'Write specs engineers and designers love'),
  (md5('course:product-management-foundations')::uuid, 3, 'Build and communicate a credible roadmap'),
  -- 6 effective-sales-conversations
  (md5('course:effective-sales-conversations')::uuid, 0, 'Open conversations that earn trust quickly'),
  (md5('course:effective-sales-conversations')::uuid, 1, 'Ask questions that uncover real needs'),
  (md5('course:effective-sales-conversations')::uuid, 2, 'Handle objections without getting defensive'),
  (md5('course:effective-sales-conversations')::uuid, 3, 'Move deals forward with clear next steps'),
  -- 7 onboarding
  (md5('course:onboarding-welcome-to-the-team')::uuid, 0, 'Know how we work and where to find things'),
  (md5('course:onboarding-welcome-to-the-team')::uuid, 1, 'Set up your tools and accounts correctly'),
  (md5('course:onboarding-welcome-to-the-team')::uuid, 2, 'Understand our values and ways of working'),
  (md5('course:onboarding-welcome-to-the-team')::uuid, 3, 'Make a confident start in your first 30 days'),
  -- 8 advanced-excel
  (md5('course:advanced-excel-for-analysts')::uuid, 0, 'Automate data prep with Power Query'),
  (md5('course:advanced-excel-for-analysts')::uuid, 1, 'Build advanced PivotTables and dashboards'),
  (md5('course:advanced-excel-for-analysts')::uuid, 2, 'Use dynamic arrays and modern functions'),
  (md5('course:advanced-excel-for-analysts')::uuid, 3, 'Create models others can trust and reuse'),
  (md5('course:advanced-excel-for-analysts')::uuid, 4, 'Debug and audit complex spreadsheets'),
  -- 9 inclusive-leadership
  (md5('course:inclusive-leadership')::uuid, 0, 'Recognise and interrupt everyday bias'),
  (md5('course:inclusive-leadership')::uuid, 1, 'Design fair, consistent team processes'),
  (md5('course:inclusive-leadership')::uuid, 2, 'Create psychological safety on your team'),
  (md5('course:inclusive-leadership')::uuid, 3, 'Lead inclusive meetings and decisions')
ON CONFLICT (course_id, sequence) DO NOTHING;

-- =============================================================================
-- 7. Sections (3 per course)
-- Section id = md5('section:<slug>:<sequence>')::uuid
-- =============================================================================
INSERT INTO section (id, org_id, course_id, title, sequence)
VALUES
  -- 1 leadership-essentials
  (md5('section:leadership-essentials-new-managers:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:leadership-essentials-new-managers')::uuid, 'Making the Shift to Manager', 0),
  (md5('section:leadership-essentials-new-managers:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:leadership-essentials-new-managers')::uuid, 'Core Manager Skills', 1),
  (md5('section:leadership-essentials-new-managers:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:leadership-essentials-new-managers')::uuid, 'Putting It Together', 2),
  -- 2 workplace-safety
  (md5('section:workplace-safety-compliance-101:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:workplace-safety-compliance-101')::uuid, 'Safety Foundations', 0),
  (md5('section:workplace-safety-compliance-101:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:workplace-safety-compliance-101')::uuid, 'Hazards & Reporting', 1),
  (md5('section:workplace-safety-compliance-101:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:workplace-safety-compliance-101')::uuid, 'Check Your Knowledge', 2),
  -- 3 data-analysis-with-sql
  (md5('section:data-analysis-with-sql:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:data-analysis-with-sql')::uuid, 'SQL Foundations', 0),
  (md5('section:data-analysis-with-sql:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:data-analysis-with-sql')::uuid, 'Aggregation & Joins', 1),
  (md5('section:data-analysis-with-sql:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:data-analysis-with-sql')::uuid, 'Analytical SQL', 2),
  -- 4 cybersecurity-awareness
  (md5('section:cybersecurity-awareness:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:cybersecurity-awareness')::uuid, 'Threats You Will Meet', 0),
  (md5('section:cybersecurity-awareness:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:cybersecurity-awareness')::uuid, 'Everyday Good Habits', 1),
  (md5('section:cybersecurity-awareness:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:cybersecurity-awareness')::uuid, 'Assessment & Certification', 2),
  -- 5 product-management-foundations
  (md5('section:product-management-foundations:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:product-management-foundations')::uuid, 'What a PM Actually Does', 0),
  (md5('section:product-management-foundations:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:product-management-foundations')::uuid, 'Discovery & Prioritisation', 1),
  (md5('section:product-management-foundations:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:product-management-foundations')::uuid, 'Delivery & Roadmaps', 2),
  -- 6 effective-sales-conversations
  (md5('section:effective-sales-conversations:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:effective-sales-conversations')::uuid, 'The Consultative Mindset', 0),
  (md5('section:effective-sales-conversations:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:effective-sales-conversations')::uuid, 'Running the Conversation', 1),
  (md5('section:effective-sales-conversations:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:effective-sales-conversations')::uuid, 'Objections & Next Steps', 2),
  -- 7 onboarding
  (md5('section:onboarding-welcome-to-the-team:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:onboarding-welcome-to-the-team')::uuid, 'Welcome', 0),
  (md5('section:onboarding-welcome-to-the-team:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:onboarding-welcome-to-the-team')::uuid, 'How We Work', 1),
  (md5('section:onboarding-welcome-to-the-team:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:onboarding-welcome-to-the-team')::uuid, 'Your First 30 Days', 2),
  -- 8 advanced-excel
  (md5('section:advanced-excel-for-analysts:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:advanced-excel-for-analysts')::uuid, 'Power Query Mastery', 0),
  (md5('section:advanced-excel-for-analysts:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:advanced-excel-for-analysts')::uuid, 'PivotTables & Modelling', 1),
  (md5('section:advanced-excel-for-analysts:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:advanced-excel-for-analysts')::uuid, 'Dashboards & Certification', 2),
  -- 9 inclusive-leadership
  (md5('section:inclusive-leadership:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:inclusive-leadership')::uuid, 'The Case for Inclusion', 0),
  (md5('section:inclusive-leadership:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:inclusive-leadership')::uuid, 'Inclusive Practices', 1),
  (md5('section:inclusive-leadership:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:inclusive-leadership')::uuid, 'Apply & Certify', 2)
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- 8. Content / lessons
-- Content id = md5('content:<slug>:<sectionSeq>:<contentSeq>')::uuid
-- =============================================================================
INSERT INTO content (id, org_id, section_id, title, content_type, duration_min, allow_preview, sequence)
VALUES
  -- ===== 1 leadership-essentials (9 lessons) =====
  (md5('content:leadership-essentials-new-managers:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:0')::uuid, 'Welcome & how to use this course', 'VIDEO', 6, true, 0),
  (md5('content:leadership-essentials-new-managers:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:0')::uuid, 'From doer to manager', 'VIDEO', 11, true, 1),
  (md5('content:leadership-essentials-new-managers:0:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:0')::uuid, 'Your first 30 days checklist', 'PDF', 8, false, 2),
  (md5('content:leadership-essentials-new-managers:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:1')::uuid, 'Running great one-on-ones', 'VIDEO', 14, false, 0),
  (md5('content:leadership-essentials-new-managers:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:1')::uuid, 'The feedback formula', 'VIDEO', 12, false, 1),
  (md5('content:leadership-essentials-new-managers:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:1')::uuid, 'Delegation without dropping balls', 'PAGE', 9, false, 2),
  (md5('content:leadership-essentials-new-managers:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:2')::uuid, 'Putting the toolkit to work', 'VIDEO', 10, false, 0),
  (md5('content:leadership-essentials-new-managers:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:2')::uuid, 'Knowledge check', 'QUIZ', 15, false, 1),
  (md5('content:leadership-essentials-new-managers:2:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:leadership-essentials-new-managers:2')::uuid, 'Earn your certificate', 'CERTIFICATION', 20, false, 2),

  -- ===== 2 workplace-safety (7 lessons) =====
  (md5('content:workplace-safety-compliance-101:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:workplace-safety-compliance-101:0')::uuid, 'Why safety matters', 'VIDEO', 7, true, 0),
  (md5('content:workplace-safety-compliance-101:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:workplace-safety-compliance-101:0')::uuid, 'Your responsibilities', 'PAGE', 6, true, 1),
  (md5('content:workplace-safety-compliance-101:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:workplace-safety-compliance-101:1')::uuid, 'Spotting common hazards', 'VIDEO', 10, false, 0),
  (md5('content:workplace-safety-compliance-101:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:workplace-safety-compliance-101:1')::uuid, 'Reporting an incident', 'VIDEO', 8, false, 1),
  (md5('content:workplace-safety-compliance-101:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:workplace-safety-compliance-101:1')::uuid, 'Emergency procedures handbook', 'PDF', 12, false, 2),
  (md5('content:workplace-safety-compliance-101:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:workplace-safety-compliance-101:2')::uuid, 'Final knowledge check', 'QUIZ', 10, false, 0),
  (md5('content:workplace-safety-compliance-101:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:workplace-safety-compliance-101:2')::uuid, 'Compliance certificate', 'CERTIFICATION', 5, false, 1),

  -- ===== 3 data-analysis-with-sql (9 lessons) =====
  (md5('content:data-analysis-with-sql:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:0')::uuid, 'Your first SELECT', 'VIDEO', 9, true, 0),
  (md5('content:data-analysis-with-sql:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:0')::uuid, 'Filtering with WHERE', 'VIDEO', 11, true, 1),
  (md5('content:data-analysis-with-sql:0:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:0')::uuid, 'SQL quick-reference', 'PDF', 7, false, 2),
  (md5('content:data-analysis-with-sql:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:1')::uuid, 'GROUP BY and aggregates', 'VIDEO', 13, false, 0),
  (md5('content:data-analysis-with-sql:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:1')::uuid, 'Joining tables', 'VIDEO', 15, false, 1),
  (md5('content:data-analysis-with-sql:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:1')::uuid, 'Hands-on lab: sales data', 'SCORM', 25, false, 2),
  (md5('content:data-analysis-with-sql:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:2')::uuid, 'Window functions', 'VIDEO', 16, false, 0),
  (md5('content:data-analysis-with-sql:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:2')::uuid, 'Query patterns for analysts', 'PAGE', 10, false, 1),
  (md5('content:data-analysis-with-sql:2:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:data-analysis-with-sql:2')::uuid, 'Final assessment', 'QUIZ', 18, false, 2),

  -- ===== 4 cybersecurity-awareness (7 lessons) =====
  (md5('content:cybersecurity-awareness:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:cybersecurity-awareness:0')::uuid, 'The human side of security', 'VIDEO', 8, true, 0),
  (md5('content:cybersecurity-awareness:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:cybersecurity-awareness:0')::uuid, 'Phishing in the wild', 'VIDEO', 11, true, 1),
  (md5('content:cybersecurity-awareness:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:cybersecurity-awareness:1')::uuid, 'Passwords & MFA', 'VIDEO', 9, false, 0),
  (md5('content:cybersecurity-awareness:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:cybersecurity-awareness:1')::uuid, 'Handling sensitive data', 'PAGE', 7, false, 1),
  (md5('content:cybersecurity-awareness:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:cybersecurity-awareness:1')::uuid, 'Securing your devices', 'PDF', 8, false, 2),
  (md5('content:cybersecurity-awareness:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:cybersecurity-awareness:2')::uuid, 'Security knowledge check', 'QUIZ', 12, false, 0),
  (md5('content:cybersecurity-awareness:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:cybersecurity-awareness:2')::uuid, 'Awareness certificate', 'CERTIFICATION', 5, false, 1),

  -- ===== 5 product-management-foundations (8 lessons) =====
  (md5('content:product-management-foundations:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:0')::uuid, 'What product managers do', 'VIDEO', 10, true, 0),
  (md5('content:product-management-foundations:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:0')::uuid, 'The PM core loop', 'VIDEO', 12, true, 1),
  (md5('content:product-management-foundations:0:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:0')::uuid, 'Working with eng & design', 'PAGE', 8, false, 2),
  (md5('content:product-management-foundations:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:1')::uuid, 'Customer discovery basics', 'VIDEO', 14, false, 0),
  (md5('content:product-management-foundations:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:1')::uuid, 'Prioritisation frameworks', 'VIDEO', 11, false, 1),
  (md5('content:product-management-foundations:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:1')::uuid, 'Writing a crisp spec', 'PDF', 9, false, 2),
  (md5('content:product-management-foundations:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:2')::uuid, 'Building a roadmap', 'VIDEO', 13, false, 0),
  (md5('content:product-management-foundations:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:product-management-foundations:2')::uuid, 'Foundations quiz', 'QUIZ', 12, false, 1),

  -- ===== 6 effective-sales-conversations (8 lessons) =====
  (md5('content:effective-sales-conversations:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:0')::uuid, 'Selling is helping', 'VIDEO', 8, true, 0),
  (md5('content:effective-sales-conversations:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:0')::uuid, 'Building rapport fast', 'VIDEO', 10, true, 1),
  (md5('content:effective-sales-conversations:0:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:0')::uuid, 'Discovery question bank', 'PDF', 7, false, 2),
  (md5('content:effective-sales-conversations:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:1')::uuid, 'Asking better questions', 'VIDEO', 12, false, 0),
  (md5('content:effective-sales-conversations:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:1')::uuid, 'Active listening', 'VIDEO', 9, false, 1),
  (md5('content:effective-sales-conversations:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:1')::uuid, 'Role-play scenarios', 'PAGE', 11, false, 2),
  (md5('content:effective-sales-conversations:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:2')::uuid, 'Handling objections', 'VIDEO', 13, false, 0),
  (md5('content:effective-sales-conversations:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:effective-sales-conversations:2')::uuid, 'Conversation skills quiz', 'QUIZ', 10, false, 1),

  -- ===== 7 onboarding (6 lessons) =====
  (md5('content:onboarding-welcome-to-the-team:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:onboarding-welcome-to-the-team:0')::uuid, 'A warm welcome', 'VIDEO', 5, true, 0),
  (md5('content:onboarding-welcome-to-the-team:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:onboarding-welcome-to-the-team:0')::uuid, 'Meet the team', 'PAGE', 6, true, 1),
  (md5('content:onboarding-welcome-to-the-team:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:onboarding-welcome-to-the-team:1')::uuid, 'Tools & accounts setup', 'PAGE', 9, false, 0),
  (md5('content:onboarding-welcome-to-the-team:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:onboarding-welcome-to-the-team:1')::uuid, 'Our values & ways of working', 'VIDEO', 8, false, 1),
  (md5('content:onboarding-welcome-to-the-team:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:onboarding-welcome-to-the-team:2')::uuid, 'Your 30-day plan', 'PDF', 7, false, 0),
  (md5('content:onboarding-welcome-to-the-team:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:onboarding-welcome-to-the-team:2')::uuid, 'Helpful links & directory', 'LINK', 4, false, 1),

  -- ===== 8 advanced-excel (9 lessons) =====
  (md5('content:advanced-excel-for-analysts:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:0')::uuid, 'Power Query: load & clean', 'VIDEO', 12, true, 0),
  (md5('content:advanced-excel-for-analysts:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:0')::uuid, 'Merging & appending queries', 'VIDEO', 14, false, 1),
  (md5('content:advanced-excel-for-analysts:0:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:0')::uuid, 'Interactive lab: transform data', 'SCORM', 22, false, 2),
  (md5('content:advanced-excel-for-analysts:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:1')::uuid, 'PivotTables, beyond the basics', 'VIDEO', 13, false, 0),
  (md5('content:advanced-excel-for-analysts:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:1')::uuid, 'Data models & relationships', 'VIDEO', 15, false, 1),
  (md5('content:advanced-excel-for-analysts:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:1')::uuid, 'Dynamic arrays & LET/LAMBDA', 'PAGE', 10, false, 2),
  (md5('content:advanced-excel-for-analysts:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:2')::uuid, 'Building a dashboard', 'VIDEO', 16, false, 0),
  (md5('content:advanced-excel-for-analysts:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:2')::uuid, 'Mastery quiz', 'QUIZ', 14, false, 1),
  (md5('content:advanced-excel-for-analysts:2:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:advanced-excel-for-analysts:2')::uuid, 'Advanced Excel certificate', 'CERTIFICATION', 6, false, 2),

  -- ===== 9 inclusive-leadership (7 lessons) =====
  (md5('content:inclusive-leadership:0:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:inclusive-leadership:0')::uuid, 'Why inclusion drives performance', 'VIDEO', 10, true, 0),
  (md5('content:inclusive-leadership:0:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:inclusive-leadership:0')::uuid, 'Understanding bias', 'VIDEO', 12, false, 1),
  (md5('content:inclusive-leadership:1:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:inclusive-leadership:1')::uuid, 'Designing fair processes', 'VIDEO', 13, false, 0),
  (md5('content:inclusive-leadership:1:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:inclusive-leadership:1')::uuid, 'Psychological safety in practice', 'VIDEO', 11, false, 1),
  (md5('content:inclusive-leadership:1:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:inclusive-leadership:1')::uuid, 'Inclusive meetings playbook', 'PDF', 9, false, 2),
  (md5('content:inclusive-leadership:2:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:inclusive-leadership:2')::uuid, 'Reflection & action plan', 'PAGE', 12, false, 0),
  (md5('content:inclusive-leadership:2:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('section:inclusive-leadership:2')::uuid, 'Inclusive leadership certificate', 'CERTIFICATION', 8, false, 1)
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- 9. Reviews (2–3 per course)
-- review id = md5('review:<slug>:<n>')::uuid
-- =============================================================================
INSERT INTO review (id, org_id, course_id, author_name, rating, comment)
VALUES
  -- 1 leadership-essentials
  (md5('review:leadership-essentials-new-managers:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:leadership-essentials-new-managers')::uuid, 'Priya N.', 5, 'Exactly what I needed in my first month as a manager. The one-on-one framework alone was worth it.'),
  (md5('review:leadership-essentials-new-managers:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:leadership-essentials-new-managers')::uuid, 'Tom B.', 5, 'Practical and to the point. I used the feedback formula the same week.'),
  (md5('review:leadership-essentials-new-managers:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:leadership-essentials-new-managers')::uuid, 'Aisha R.', 4, 'Great content. Would have loved a few more real-world examples.'),
  -- 2 workplace-safety
  (md5('review:workplace-safety-compliance-101:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:workplace-safety-compliance-101')::uuid, 'Dan K.', 5, 'Clear and quick. Finally a compliance course that respects your time.'),
  (md5('review:workplace-safety-compliance-101:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:workplace-safety-compliance-101')::uuid, 'Maria S.', 4, 'Solid overview of the essentials. The reporting walkthrough was helpful.'),
  -- 3 data-analysis-with-sql
  (md5('review:data-analysis-with-sql:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:data-analysis-with-sql')::uuid, 'Wei L.', 5, 'Best practical SQL course I have taken. The window functions section is gold.'),
  (md5('review:data-analysis-with-sql:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:data-analysis-with-sql')::uuid, 'Sofia M.', 5, 'The hands-on lab made everything click. Highly recommend.'),
  (md5('review:data-analysis-with-sql:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:data-analysis-with-sql')::uuid, 'Raj P.', 4, 'Great pacing. A challenge dataset at the end would be a nice addition.'),
  -- 4 cybersecurity-awareness
  (md5('review:cybersecurity-awareness:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:cybersecurity-awareness')::uuid, 'Elena V.', 5, 'I now actually spot phishing emails. Engaging and not preachy.'),
  (md5('review:cybersecurity-awareness:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:cybersecurity-awareness')::uuid, 'James O.', 4, 'Good refresher with memorable examples.'),
  -- 5 product-management-foundations
  (md5('review:product-management-foundations:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:product-management-foundations')::uuid, 'Hana T.', 5, 'A fantastic on-ramp to PM. The discovery section changed how I work.'),
  (md5('review:product-management-foundations:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:product-management-foundations')::uuid, 'Leo G.', 4, 'Clear and well structured. Wanted a bit more on metrics.'),
  -- 6 effective-sales-conversations
  (md5('review:effective-sales-conversations:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:effective-sales-conversations')::uuid, 'Carla D.', 5, 'My discovery calls are completely different now. Less pitching, more listening.'),
  (md5('review:effective-sales-conversations:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:effective-sales-conversations')::uuid, 'Ben H.', 5, 'The objection-handling module is excellent.'),
  (md5('review:effective-sales-conversations:2')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:effective-sales-conversations')::uuid, 'Nadia F.', 4, 'Very useful. The role-play scenarios were my favourite part.'),
  -- 7 onboarding
  (md5('review:onboarding-welcome-to-the-team:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:onboarding-welcome-to-the-team')::uuid, 'Omar A.', 5, 'Made my first week so much smoother. Warm and genuinely helpful.'),
  (md5('review:onboarding-welcome-to-the-team:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:onboarding-welcome-to-the-team')::uuid, 'Grace L.', 5, 'Loved the tone. Everything I needed in one place.'),
  -- 8 advanced-excel
  (md5('review:advanced-excel-for-analysts:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:advanced-excel-for-analysts')::uuid, 'Victor S.', 5, 'Power Query finally makes sense. The lab was brilliant.'),
  (md5('review:advanced-excel-for-analysts:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:advanced-excel-for-analysts')::uuid, 'Mei C.', 4, 'Advanced and worth it. Dynamic arrays section is excellent.'),
  -- 9 inclusive-leadership
  (md5('review:inclusive-leadership:0')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:inclusive-leadership')::uuid, 'Fatima Z.', 5, 'Thoughtful and practical. The psychological-safety module is a must.'),
  (md5('review:inclusive-leadership:1')::uuid, '00000000-0000-0000-0000-0000000000ca', md5('course:inclusive-leadership')::uuid, 'Daniel K.', 5, 'Our leadership team went through this together and it sparked real change.')
ON CONFLICT (id) DO NOTHING;
