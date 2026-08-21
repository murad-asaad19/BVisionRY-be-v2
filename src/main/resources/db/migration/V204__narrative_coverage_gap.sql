-- =============================================================================
-- V204 — the "nothing disappears" flag (second reading redesign, Aug 2026).
--
-- A narrative whose model output left baseline items unaccounted for — even
-- after the corrective retry — gets code-synthesised fallback items appended
-- and this flag set, so the review UI can point the coach straight at the rows
-- that need a human eye. Such a row is forced to DRAFT even under
-- auto-approve; the flag is what tells the reviewer WHY it is sitting there.
--
-- No prompt-template change here: the coverage protocol (numbered BEFORE
-- items + the "covers" declaration) rides every call from Java
-- (ShiftNarrativeService.COVERAGE_NOTE), so it binds on admin-customised
-- templates too — same stance as V203's RESOLVED_NOTE.
-- =============================================================================

ALTER TABLE shift_narratives
    ADD COLUMN coverage_gap boolean NOT NULL DEFAULT false;
