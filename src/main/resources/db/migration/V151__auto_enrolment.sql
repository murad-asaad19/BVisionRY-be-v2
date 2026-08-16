-- =============================================================================
-- V151__auto_enrolment.sql — the auto-enrolment ledger: what the engine decided
-- for one founder, one course, one evaluation, and WHY (roadmap §7 item 10).
-- =============================================================================
-- Expand-only: one new table plus one index. Nothing dropped, renamed or
-- narrowed. Amended in-branch during the ticket's fix cycle, which is safe for
-- exactly one reason: this file has never left the branch, so no environment has
-- applied it and no Flyway checksum exists to break.
-- Notably `enrollment` is NOT altered — an auto-enrolment writes an
-- ordinary row there and is deliberately indistinguishable from one a founder
-- made themselves, so the player, progress and certificates never have to care
-- how someone arrived.
--
-- ONE TABLE, THREE JOBS. It could have been three mechanisms; it is one row:
--
--   1. THE IDEMPOTENCY KEY — uq_auto_enrolments (user_id, course_id,
--      submission_id) is exactly agent-policy's
--      auto_enrolment_idempotency_key: [founder_id, course_id,
--      source_evaluation_id]. `submission_id` IS the source evaluation (one
--      submission = one assessment run = one set of pillar_evaluations).
--      Re-processing the same evaluation finds its own rows and does nothing; a
--      NEW evaluation carries a new submission_id and may add courses the
--      founder does not have yet. It can never add a SECOND enrolment in a
--      course they already have — that is uq_enrollment_user_course (V79), and
--      it is why the policy's NEW_ENROLMENTS_ONLY holds regardless of how many
--      evaluations point at the same course.
--
--   2. THE WHY — pillar_id + band_position are what a founder is shown as
--      "recommended because of <pillar>" (dashboard_recommendations, the next
--      ticket). STORED, not re-derived: a pillar's band labels can be re-worded
--      long after the founder was measured, so the reason has to be captured at
--      the moment it was true.
--
--   3. THE AUDIT TRAIL — `outcome` distinguishes a write from an idempotent
--      no-op from a refusal, so "why wasn't X enrolled?" has an answer without a
--      second audit framework. A refusal writes a row; only an off-axis pillar
--      (a stored label naming no current band) writes nothing at all, because
--      there is no course to attribute it to.
--
-- NO org_id, deliberately. An enrolment is (user, course) and nothing else —
-- `enrollment` has modelled it that way since V79 because the catalog is a
-- public cross-org surface ("the public catalog returns ALL PUBLISHED courses
-- regardless of which org created them", CourseRepository). Platform-declared
-- mappings pointing an org's founder at another org's PUBLISHED course is the
-- intended shape, not a leak; attribution is user_id, and the org is whatever
-- that user's membership says at read time.
--
-- FKs cascade on EVERY side so this table can never outlive what it explains: a
-- deleted founder, course, submission or pillar takes its rows with it rather
-- than leaving a reason that resolves to nothing. Same rule V150 applies to the
-- rules themselves. That is a schema-level dependency on the auth, catalog and
-- assessment tables, not a Java one — the pipeline package imports no type from
-- any of them (the enrolment INSERT goes through NamedParameterJdbcTemplate, as
-- in insights.BenchmarkReadRepository).
-- =============================================================================

CREATE TABLE auto_enrolments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users       (id) ON DELETE CASCADE,
    course_id     UUID NOT NULL REFERENCES course      (id) ON DELETE CASCADE,
    submission_id UUID NOT NULL REFERENCES submissions (id) ON DELETE CASCADE,
    pillar_id     UUID NOT NULL REFERENCES pillars     (id) ON DELETE CASCADE,
    -- The band the founder's STORED maturity label resolved to: the 0-based
    -- ordinal position within that pillar's own set, lowest first (RULING 4,
    -- V150). Not bounded above by a CHECK for the same reason as V150 — the
    -- upper bound is mutable data.
    band_position INTEGER NOT NULL,
    outcome       VARCHAR(32) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_auto_enrolments_band_position CHECK (band_position >= 0),
    -- Mirrors the AutoEnrolmentOutcome enum. ENROLLED = a row was written to
    -- `enrollment`; ALREADY_ENROLLED = the founder had it already and it was
    -- left exactly as it was; COURSE_NOT_PUBLISHED = the mapped course is DRAFT
    -- or ARCHIVED and nobody was enrolled. The mapping surface deliberately does
    -- not filter by state, so that refusal is the engine's to make and record.
    -- CEILING: a CHECK pins the value set in the schema, so adding a FOURTH
    -- outcome needs DROP CONSTRAINT then re-ADD. That is a CONTRACTION, which
    -- this run's policy reserves to a human (never_auto_decide) — an agent
    -- widening this list must park and ask, not write the migration. The trade is
    -- deliberate: three values are the whole decision space today, and a CHECK
    -- makes a typo'd outcome impossible rather than merely unlikely.
    CONSTRAINT ck_auto_enrolments_outcome
        CHECK (outcome IN ('ENROLLED', 'ALREADY_ENROLLED', 'COURSE_NOT_PUBLISHED')),
    CONSTRAINT uq_auto_enrolments UNIQUE (user_id, course_id, submission_id)
);

-- "What did this evaluation decide, and why wasn't X enrolled?" — the engine's
-- idempotency read and the operator's question, same query. The unique
-- constraint's index already serves the founder-facing read (user_id first).
CREATE INDEX idx_auto_enrolments_submission ON auto_enrolments (submission_id);
