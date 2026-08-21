-- =============================================================================
-- V207__session_pillars.sql — "this session grows this pillar"
-- Modeled on program_task_pillars (V187): a plain join table, written only by
-- the session upsert's full replace. THE TAG IS THE DISTANCE PILLAR ID, for
-- V187's reason: narratives are keyed per cohort by distance_pillar_id, so the
-- tag lines up 1:1 with the narrative it feeds. The upsert validates each id
-- against the cohort's fully-mapped pairs; the schema cannot express that.
--
-- Both FKs cascade: the row is a statement about two things that exist, and
-- must not outlive either. Tagging is OPTIONAL and retroactive-safe — changed
-- tags reach narratives only when an admin regenerates them.
-- =============================================================================

CREATE TABLE session_pillars (
    session_id uuid NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    pillar_id  uuid NOT NULL REFERENCES pillars (id) ON DELETE CASCADE,
    PRIMARY KEY (session_id, pillar_id)
);

-- The narrative's read: "which sessions feed this pillar". The PK already
-- serves the session-side read and the session FK's cascade.
CREATE INDEX ix_session_pillars_pillar ON session_pillars (pillar_id);
