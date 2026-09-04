-- =============================================================================
-- V215__coaching_session_tasks.sql — coaching sessions as cohort tasks
-- (web/docs/coaching-sessions-spec.md §2)
-- =============================================================================
-- A COACHING_SESSION task is a per-member BOOKING against a coach's calendar,
-- configured by the super admin on the cohort board between other tasks. The
-- booking IS a `sessions` row (type COACHING_1ON1, expected attendee = the one
-- member) with the extra columns below, so the Sessions tab, Bvisionry Labs,
-- the founder profile and participation scoring see it with zero new joins.
-- The coach's calendar (weekly windows + blackouts) lives in the coaching
-- slice; the survey the member fills in afterwards is named on the task.
-- =============================================================================

-- 1 ── the task type + its config --------------------------------------------

ALTER TABLE program_tasks
    DROP CONSTRAINT ck_program_tasks_task_type;
ALTER TABLE program_tasks
    ADD CONSTRAINT ck_program_tasks_task_type
        CHECK (task_type IN ('LESSON', 'COURSE', 'EXERCISE', 'ASSESSMENT',
                             'WORKSHOP', 'SURVEY', 'COACHING_SESSION'));

-- Config on the row (no ref_id — like LESSON, the task owns its own settings).
-- post_session_survey_id is a bare uuid, the cross-slice convention.
ALTER TABLE program_tasks
    ADD COLUMN duration_minutes       integer,
    ADD COLUMN post_session_survey_id uuid;

ALTER TABLE program_tasks
    ADD CONSTRAINT ck_program_tasks_duration
        CHECK (duration_minutes IS NULL OR duration_minutes BETWEEN 15 AND 240);

-- 2 ── coach calendar ---------------------------------------------------------

-- The zone the coach's weekly windows are expressed in (IANA id). Null until
-- the coach saves availability for the first time.
ALTER TABLE coach_profiles
    ADD COLUMN time_zone varchar(64);

-- Recurring weekly windows. ISO weekday: 1 = Monday … 7 = Sunday.
CREATE TABLE coach_availability_rules (
    id         uuid     NOT NULL DEFAULT gen_random_uuid(),
    coach_id   uuid     NOT NULL,
    weekday    smallint NOT NULL,
    start_time time     NOT NULL,
    end_time   time     NOT NULL,

    CONSTRAINT pk_coach_availability_rules PRIMARY KEY (id),
    CONSTRAINT fk_coach_availability_rules_coach FOREIGN KEY (coach_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_coach_availability_rules_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_coach_availability_rules_span CHECK (end_time > start_time)
);

CREATE INDEX ix_coach_availability_rules_coach ON coach_availability_rules (coach_id);

-- Time off / one-off blackouts, as instants.
CREATE TABLE coach_availability_blocks (
    id        uuid        NOT NULL DEFAULT gen_random_uuid(),
    coach_id  uuid        NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at   timestamptz NOT NULL,
    reason    varchar(200),

    CONSTRAINT pk_coach_availability_blocks PRIMARY KEY (id),
    CONSTRAINT fk_coach_availability_blocks_coach FOREIGN KEY (coach_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_coach_availability_blocks_span CHECK (ends_at > starts_at)
);

CREATE INDEX ix_coach_availability_blocks_coach ON coach_availability_blocks (coach_id, starts_at);

-- 3 ── bookings are sessions --------------------------------------------------

ALTER TABLE sessions
    ADD COLUMN program_task_id uuid,
    ADD COLUMN member_id       uuid,
    ADD COLUMN coach_id        uuid,
    ADD COLUMN ends_at         timestamptz,
    ADD COLUMN booking_status  varchar(20),
    ADD COLUMN completed_at    timestamptz;

-- GDPR: the booking is ABOUT the member and dies with them; the coach is an
-- attribution and is SET NULL like created_by / marked_by. The task is a bare
-- uuid (cross-slice convention) — the board's own delete guard refuses to drop
-- a task members have booked.
ALTER TABLE sessions
    ADD CONSTRAINT fk_sessions_member FOREIGN KEY (member_id)
        REFERENCES users (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_sessions_coach FOREIGN KEY (coach_id)
        REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_sessions_booking_status
        CHECK (booking_status IS NULL
               OR booking_status IN ('SCHEDULED', 'COMPLETED', 'NO_SHOW')),
    -- A plain session carries none of the booking columns; a booking carries
    -- them all (coach_id may go null only via the GDPR SET NULL above).
    ADD CONSTRAINT ck_sessions_booking_shape
        CHECK ((program_task_id IS NULL AND member_id IS NULL AND coach_id IS NULL
                AND ends_at IS NULL AND booking_status IS NULL AND completed_at IS NULL)
               OR (program_task_id IS NOT NULL AND member_id IS NOT NULL
                   AND ends_at IS NOT NULL AND booking_status IS NOT NULL
                   AND ends_at > session_date));

-- One booking per (task, member): the race gate behind the app-level check.
CREATE UNIQUE INDEX ux_sessions_task_member
    ON sessions (program_task_id, member_id)
    WHERE program_task_id IS NOT NULL;

CREATE INDEX ix_sessions_coach_start
    ON sessions (coach_id, session_date)
    WHERE coach_id IS NOT NULL;

-- A coach never holds two overlapping SCHEDULED bookings — enforced by the
-- database, not by application locking. btree_gist is a trusted contrib
-- extension (PG13+), like pgcrypto in V76.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE sessions
    ADD CONSTRAINT ex_sessions_coach_no_overlap
        EXCLUDE USING gist (coach_id WITH =, tstzrange(session_date, ends_at) WITH &&)
        WHERE (booking_status = 'SCHEDULED');
