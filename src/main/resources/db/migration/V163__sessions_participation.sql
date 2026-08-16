-- =============================================================================
-- V163__sessions_participation.sql — Sessions + attendance (redesign spec §4)
-- =============================================================================
-- A session is one held (or scheduled) cohort event of a participation type:
-- workshop, 1:1 coaching, or group coaching. Attendance is a PRESENCE-ROW
-- model: a row exists only when an admin ticked the member present, so the
-- roll call naturally starts unticked and every tick carries its §7b stamp
-- (marked_at, marked_by). Expected attendees default to the cohort's members
-- at read time; session_expected_attendees narrows a session (e.g. a 1:1) to
-- the listed members only.
--
-- Session types are keyed to the participation-config category keys
-- (workshops / coaching_1on1 / coaching_group); a type with no matching
-- config category simply contributes nothing to the score.
--
-- GDPR: attendance is ABOUT the member and dies with them (FK CASCADE);
-- created_by / marked_by are admin attributions and SET NULL like elsewhere.
-- =============================================================================

CREATE TABLE sessions (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id       uuid        NOT NULL,
    cohort_id    uuid        NOT NULL,
    type         varchar(20) NOT NULL,
    title        varchar(200),
    session_date timestamptz NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_sessions PRIMARY KEY (id),
    CONSTRAINT fk_sessions_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_sessions_cohort FOREIGN KEY (cohort_id)
        REFERENCES cohorts (id) ON DELETE CASCADE,
    CONSTRAINT fk_sessions_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_sessions_type CHECK (type IN ('WORKSHOP', 'COACHING_1ON1', 'COACHING_GROUP'))
);

CREATE INDEX ix_sessions_cohort ON sessions (cohort_id, session_date DESC);

-- Expected attendees for a session. EMPTY = the whole cohort is expected
-- (workshops / group coaching need no extra input); rows present = only the
-- listed members are expected — a 1:1 names its one founder, so everyone
-- else's participation denominator (and history) excludes the session.
CREATE TABLE session_expected_attendees (
    session_id uuid NOT NULL,
    member_id  uuid NOT NULL,

    CONSTRAINT pk_session_expected_attendees PRIMARY KEY (session_id, member_id),
    CONSTRAINT fk_session_expected_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_expected_member FOREIGN KEY (member_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE session_attendance (
    session_id uuid        NOT NULL,
    member_id  uuid        NOT NULL,
    marked_at  timestamptz NOT NULL DEFAULT now(),
    marked_by  uuid,

    CONSTRAINT pk_session_attendance PRIMARY KEY (session_id, member_id),
    CONSTRAINT fk_session_attendance_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_attendance_member FOREIGN KEY (member_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_attendance_marked_by FOREIGN KEY (marked_by)
        REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX ix_session_attendance_member ON session_attendance (member_id);
