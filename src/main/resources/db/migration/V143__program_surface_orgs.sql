-- Explicit Program Flow / Workshops console membership.
--
-- Until now an organization was "in" a console iff it happened to have a
-- cohort / workshop, so taking it off a console meant DELETING that data.
-- Membership is now a row here, so an org can be removed from a console while
-- its cohorts, workshops and all their learner data are kept — deleting them
-- becomes a separate, opt-in destructive choice.

CREATE TABLE program_surface_orgs (
    org_id  uuid        NOT NULL,
    surface varchar(20) NOT NULL,
    CONSTRAINT pk_program_surface_orgs PRIMARY KEY (org_id, surface),
    CONSTRAINT ck_program_surface_orgs_surface
        CHECK (surface IN ('PROGRAM_FLOW', 'WORKSHOPS')),
    CONSTRAINT fk_program_surface_orgs_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE
);

-- Backfill reproduces today's derived behaviour exactly: whatever the consoles
-- currently list stays listed.
INSERT INTO program_surface_orgs (org_id, surface)
SELECT DISTINCT c.org_id, 'PROGRAM_FLOW' FROM cohorts c;

INSERT INTO program_surface_orgs (org_id, surface)
SELECT DISTINCT w.org_id, 'WORKSHOPS' FROM workshops w;
