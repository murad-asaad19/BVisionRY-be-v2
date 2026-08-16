-- =============================================================================
-- V184: who is an enrolment override ABOUT — a person, or an org-wide removal?
--
-- V157's enrolment_overrides row is the exclusion that makes a removal HOLD:
-- the self-enrol path refuses, and the org-rule read filters it forever. But
-- the rows removeForEveryone stamps (one per member the cancel hit) were
-- indistinguishable from a by-name removeForMember exclusion — so a later
-- org-wide RE-assign gave the course back to everyone EXCEPT the members who
-- had actually started it (the only ones with a materialized row to exclude).
--
-- Operator ruling (2026-08-16): an org-wide re-assign undoes an org-wide
-- removal's own exclusions; a by-name removal — a statement about a PERSON —
-- still holds until an explicit by-name assign clears it.
--
-- scope = 'MEMBER': written by removeForMember; sticky across rule
--                   delete/re-create.
-- scope = 'ORG':    written by removeForEveryone's blanket stamp; cleared by
--                   the next org-wide assign of the same course
--                   (OrgCourseService.assign -> clearOrgScopeExclusions).
--
-- Expand-only, no backfill: every existing row predates the blanket stamp's
-- distinguishability, and 'MEMBER' (the sticky reading) is the safe default.
-- Reads stay scope-agnostic — an ORG-scope exclusion suppresses the rule and
-- blocks self-enrol exactly like a MEMBER one until an admin re-assigns.
-- =============================================================================

ALTER TABLE enrolment_overrides
    ADD COLUMN scope TEXT NOT NULL DEFAULT 'MEMBER'
        CONSTRAINT ck_enrolment_overrides_scope CHECK (scope IN ('MEMBER', 'ORG'));
