-- =============================================================================
-- V150__pillar_course_mapping.sql — "this pillar, at this band, means these
-- courses" (roadmap §7 item 9). Config only: the enrolment engine that reads it
-- is the NEXT ticket, so nothing here writes or implies an enrolment.
-- =============================================================================
-- Expand-only: one new table plus its indexes. No column dropped, renamed or
-- narrowed anywhere.
--
-- BAND IDENTITY IS ORDINAL POSITION, NOT A NAME (agent-decisions RULING 4).
-- Maturity bands are per-pillar CONFIGURABLE data in
-- `pillars.maturity_thresholds_json`; 22 distinct threshold configurations are
-- live and customers run bespoke vocabularies ("Redline / Balanced / Battery
-- Charged"), so there is no shared name set to key on. `band_position` is the
-- 0-based index of the band within THAT pillar's own set ordered lowest ->
-- highest by its minimum score — the same order MaturityThresholdValidator
-- enforces (contiguous 1..N partition of 0-100) and the same order the editor
-- renders. Position 0 is always the weakest band, which is the one that matters:
-- a founder scoring at the bottom of a pillar is exactly who needs a course.
--
-- WHAT HAPPENS WHEN AN ADMIN LATER EDITS THE BAND SET. Nothing, deliberately —
-- no cascade, no rewrite, no block:
--   · Positions are never renumbered. A mapping keeps pointing at position k,
--     and every read resolves k against the pillar's CURRENT bands.
--   · A shrunk band set STRANDS the positions past its end. Those rows are
--     inert by construction: a score maps to a position that exists, so a
--     position that no longer exists can never be selected. They are kept, not
--     deleted — the read marks them (null band label) and the admin UI names
--     the fix. Deleting them would destroy config the admin never asked to lose
--     on an edit that is itself reversible; blocking the band edit would make
--     the instrument hostage to a downstream recommendation.
-- Re-widening the band set makes a stranded rule live again, which is the same
-- undo the admin already expects from every other reversible edit here.
--
-- THE SAFETY ARGUMENT IS THE DRAFT FREEZE, NOT RE-LABELLING. Re-labelling a
-- band is visible; a same-label re-SPLIT is not. Going from
-- {Weak 0-49, Ready 50-100} to {Weak 0-19, Middling 20-49, Ready 50-100} leaves
-- position 0 still reading "Weak" while the rule it carries silently narrows
-- from 0-49 to 0-19, with nothing on screen changing. What makes that harmless
-- is that PillarService.update calls requireDraft: a PUBLISHED pipeline's bands
-- cannot move at all, so a re-split can only happen on a draft — before anyone
-- has been measured or enrolled by it. Relaxing that freeze invalidates this
-- whole scheme, not just this comment.
--
-- OWNERSHIP GRAIN IS PLATFORM, NOT PER-ORG, hence no org_id column. Both sides
-- of the relation are already platform-wide: pipelines/pillars are global
-- content that only SUPER_ADMIN may author (PipelineController is class-level
-- SUPER_ADMIN — an ORG_ADMIN cannot even read a pillar), and the catalog
-- deliberately does not filter by org ("the public catalog returns ALL
-- PUBLISHED courses regardless of which org created them", CourseRepository).
-- A per-org mapping would need an org-scoped pillar set and an org-scoped course
-- set, neither of which exists.
--
-- FKs cascade on BOTH sides so this table can never outlive what it references:
-- deleting a pillar or a course removes its rules rather than leaving an id that
-- resolves to nothing. That is a schema-level dependency on the catalog table,
-- not a Java one — no catalog type is imported by the pipeline package (the
-- title read goes through NamedParameterJdbcTemplate, as in
-- insights.BenchmarkReadRepository).
-- =============================================================================

CREATE TABLE pillar_course_mappings (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pillar_id     UUID NOT NULL REFERENCES pillars (id) ON DELETE CASCADE,
    -- 0-based, lowest band first. Bounded above only in the service, against the
    -- pillar's band count AT WRITE TIME: the upper bound is mutable data, so a
    -- CHECK here would either be wrong or freeze the band set.
    band_position INTEGER NOT NULL,
    course_id     UUID NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_pillar_course_mappings_band_position CHECK (band_position >= 0),
    -- One course is recommended at most once per (pillar, band). The next
    -- ticket's idempotency key is [founder, course, evaluation]; a duplicate
    -- rule here would make that key do work the config should never have
    -- created.
    CONSTRAINT uq_pillar_course_mappings UNIQUE (pillar_id, band_position, course_id)
);

-- The read the enrolment engine will make: "band k of pillar p -> which courses".
CREATE INDEX idx_pillar_course_mappings_pillar_band
    ON pillar_course_mappings (pillar_id, band_position);

-- Supports the FK's own cascade and "which pillars point at this course".
CREATE INDEX idx_pillar_course_mappings_course
    ON pillar_course_mappings (course_id);
