-- V168__course_wiring.sql — redesign spec §3: courses <-> organizations.
--
-- Five moves, one migration (phase G ships alone):
--   1. enrollment learns WHY it exists (source), WHO assigned it, whether it is
--      REQUIRED and by WHEN.
--   2. org-wide assignment becomes a RULE (org_course_rules) evaluated at read
--      time, not N enrollment rows — new members are covered automatically and
--      unassignment is one delete. Per-member opt-out reuses the existing
--      enrolment_overrides table (V157): member-level beats org-level.
--   3. per-course visibility for the ORG world (everyone / minimum tier /
--      explicit org list). Distinct from the pre-existing course.visibility
--      (PUBLIC/UNLISTED/...) and course.access, which are the PUBLIC catalog's
--      own knobs — hence the org_ prefix.
--   4. AI rules gain a MODE: Auto-assign (today's behaviour) or Suggest.
--   5. Suggest mode records a SUGGESTED row on the existing auto_enrolments
--      ledger — NOT an enrollment. See the note on ck_auto_enrolments_outcome.

/* ---------------------------------------------------------------- 1. enrollment */

ALTER TABLE enrollment
    ADD COLUMN source      VARCHAR(20) NOT NULL DEFAULT 'SELF',
    ADD COLUMN assigned_by UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN deadline    TIMESTAMPTZ,
    ADD COLUMN required    BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE enrollment
    ADD CONSTRAINT ck_enrollment_source
        CHECK (source IN ('ORG_RULE', 'DIRECT', 'AI_SUGGESTED', 'SELF'));

-- Backfill: the ONLY provenance the schema has ever recorded is the
-- auto-enrolment ledger, so a row the engine wrote is AI_SUGGESTED and
-- everything else is SELF. Direct and org-rule assignments did not exist before
-- this migration, so no row can honestly be labelled either — an enrollment that
-- an admin created by hand pre-V168 is indistinguishable from a self-enrolment
-- and is deliberately NOT guessed at.
UPDATE enrollment e
   SET source = 'AI_SUGGESTED'
 WHERE EXISTS (SELECT 1
                 FROM auto_enrolments a
                WHERE a.user_id = e.user_id
                  AND a.course_id = e.course_id
                  AND a.outcome = 'ENROLLED');

-- Overdue reads (needs-attention strip, journey deadline chips) scan by date.
CREATE INDEX ix_enrollment_deadline ON enrollment (deadline) WHERE deadline IS NOT NULL;

/* ------------------------------------------------------- 2. org-wide rules */

CREATE TABLE org_course_rules (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    course_id  UUID NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    required   BOOLEAN NOT NULL DEFAULT FALSE,
    deadline   TIMESTAMPTZ,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    -- Re-assigning an existing rule (new required flag / new deadline) must not
    -- rewrite who first made the org's curation decision, so the §7b stamp the
    -- Courses tab shows is COALESCE(updated_by, created_by) over updated_at.
    updated_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_course_rules UNIQUE (org_id, course_id)
);

CREATE INDEX idx_org_course_rules_org ON org_course_rules (org_id);
CREATE INDEX idx_org_course_rules_course ON org_course_rules (course_id);

COMMENT ON TABLE org_course_rules IS
    'Spec §3: org-wide course assignment is a RULE evaluated at read time, not N '
    'enrollment rows. Member-level opt-out = an enrolment_overrides row (V157).';

/* --------------------------------------------------------- 3. visibility */

ALTER TABLE course
    ADD COLUMN org_visibility            VARCHAR(20) NOT NULL DEFAULT 'EVERYONE',
    ADD COLUMN org_visibility_min_tier   VARCHAR(32),
    ADD COLUMN org_visibility_updated_at TIMESTAMPTZ,
    ADD COLUMN org_visibility_updated_by UUID REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE course
    ADD CONSTRAINT ck_course_org_visibility
        CHECK (org_visibility IN ('EVERYONE', 'MIN_TIER', 'ORG_LIST'));

-- MIN_TIER needs a tier; the other two modes must not carry a stale one.
ALTER TABLE course
    ADD CONSTRAINT ck_course_org_visibility_tier
        CHECK ((org_visibility = 'MIN_TIER') = (org_visibility_min_tier IS NOT NULL));

CREATE TABLE course_visible_orgs (
    course_id  UUID NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    org_id     UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (course_id, org_id)
);

CREATE INDEX idx_course_visible_orgs_org ON course_visible_orgs (org_id);

/* ----------------------------------------------------------- 4. AI mode */

ALTER TABLE pillar_course_mappings
    ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'AUTO_ASSIGN';

ALTER TABLE pillar_course_mappings
    ADD CONSTRAINT ck_pillar_course_mappings_mode
        CHECK (mode IN ('SUGGEST', 'AUTO_ASSIGN'));

/* --------------------------------------------------- 5. Suggest outcome */

-- V151 pinned this CHECK and reserved widening it to a human. This IS that
-- decision: Suggest mode needs a fourth outcome because a suggestion is a
-- DECISION the engine made and must be able to explain ("recommended because of
-- Focus & Flow"), while deliberately NOT being an enrollment. accepted_at is the
-- §7b stamp for the one-tap Accept that turns it into one.
ALTER TABLE auto_enrolments DROP CONSTRAINT ck_auto_enrolments_outcome;
ALTER TABLE auto_enrolments
    ADD CONSTRAINT ck_auto_enrolments_outcome
        CHECK (outcome IN ('ENROLLED', 'ALREADY_ENROLLED', 'COURSE_NOT_PUBLISHED', 'SUGGESTED'));

ALTER TABLE auto_enrolments ADD COLUMN accepted_at TIMESTAMPTZ;

CREATE INDEX idx_auto_enrolments_open_suggestions
    ON auto_enrolments (user_id) WHERE outcome = 'SUGGESTED' AND accepted_at IS NULL;
