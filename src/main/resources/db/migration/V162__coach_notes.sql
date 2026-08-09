-- =============================================================================
-- V162__coach_notes.sql — Coach notes (redesign spec §2.2 / §2.4)
-- =============================================================================
-- A private note a coach writes about one founder. Visible to the writing
-- coach, any other coach who can see the founder, and org admins (labeled with
-- the coach's name) — NEVER to the member: there is no member-facing endpoint
-- and no member-readable surface reads this table.
--
-- GDPR: the note is ABOUT the member and BY the coach; it dies with either
-- (both FKs CASCADE). org_id is denormalised so every read is tenant-scoped in
-- one predicate, matching coach_assignments.
-- =============================================================================

CREATE TABLE coach_notes (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL,
    coach_id   uuid        NOT NULL,
    member_id  uuid        NOT NULL,
    body       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_coach_notes PRIMARY KEY (id),
    CONSTRAINT fk_coach_notes_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_coach_notes_coach FOREIGN KEY (coach_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_coach_notes_member FOREIGN KEY (member_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_coach_notes_body_not_blank CHECK (btrim(body) <> '')
);

CREATE INDEX ix_coach_notes_member ON coach_notes (org_id, member_id, created_at DESC);
CREATE INDEX ix_coach_notes_coach ON coach_notes (coach_id);
