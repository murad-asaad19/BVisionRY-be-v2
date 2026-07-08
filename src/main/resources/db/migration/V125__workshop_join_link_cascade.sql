-- Workshop-bound join links must die with their workshop, not linger as a
-- second active org-wide link.
--
-- V124 shipped join_links.workshop_id as ON DELETE SET NULL. When a workshop
-- that has an active join link is deleted, the FK nulls that link's
-- workshop_id (is_active stays true), which either:
--   (a) collides with uq_join_links_active_org (one active workshop_id-NULL
--       link per org) — the DELETE fails with a 500 and the workshop can never
--       be deleted; or
--   (b) with no org-wide link present, silently promotes the dead workshop's
--       invite into a live, still-active org-wide join link (a dangling token
--       that keeps enrolling users into the org).
--
-- Swap the delete rule to CASCADE so the link is removed with its workshop.
-- Forward migration (not an edit to V124) because V124 is already applied to
-- running environments.
ALTER TABLE join_links
    DROP CONSTRAINT join_links_workshop_id_fkey;

ALTER TABLE join_links
    ADD CONSTRAINT join_links_workshop_id_fkey
        FOREIGN KEY (workshop_id) REFERENCES workshops (id) ON DELETE CASCADE;
