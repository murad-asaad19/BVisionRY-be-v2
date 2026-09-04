-- =============================================================================
-- V216__session_tasks_v2.sql — sessions as cohort tasks, v2
-- (web/docs/coaching-sessions-spec.md §2)
-- =============================================================================
-- v1 (V215) had ONE task type, COACHING_SESSION, and a row that existed only
-- once a member had booked it. v2 widens that to all three session shapes:
-- the task type is SESSION and carries a `session_type` (1:1 / group /
-- workshop), and the `sessions` row is MATERIALISED UNSCHEDULED the moment the
-- cohort reaches the task — booking or scheduling only dates it, cancelling
-- reverts it, nothing is deleted. Held-but-absent is COMPLETED with no
-- attendance row, so NO_SHOW is gone. The coach's connected calendar (Google
-- for now) lives in its own table behind a `provider` seam.
-- =============================================================================

-- 1 ── program_tasks: the type widens, the subtype arrives ---------------------

-- Drop the old check BEFORE renaming rows: a v1 database already holds
-- COACHING_SESSION tasks, and the v1 constraint would refuse 'SESSION'.
ALTER TABLE program_tasks DROP CONSTRAINT ck_program_tasks_task_type;
UPDATE program_tasks SET task_type = 'SESSION' WHERE task_type = 'COACHING_SESSION';
ALTER TABLE program_tasks ADD CONSTRAINT ck_program_tasks_task_type
    CHECK (task_type IN ('LESSON', 'COURSE', 'EXERCISE', 'ASSESSMENT', 'SURVEY', 'SESSION'));

-- Mirrors sessions.type; every SESSION task has one, no other type may.
ALTER TABLE program_tasks ADD COLUMN session_type varchar(20);
UPDATE program_tasks SET session_type = 'COACHING_1ON1' WHERE task_type = 'SESSION';

ALTER TABLE program_tasks
    ADD CONSTRAINT ck_program_tasks_session_type
        CHECK (session_type IS NULL
               OR session_type IN ('COACHING_1ON1', 'COACHING_GROUP', 'WORKSHOP')),
    ADD CONSTRAINT ck_program_tasks_session_shape
        CHECK ((task_type = 'SESSION') = (session_type IS NOT NULL));

-- 2 ── sessions: unscheduled rows, no NO_SHOW, calendar columns ----------------

ALTER TABLE sessions ALTER COLUMN session_date DROP NOT NULL;

ALTER TABLE sessions
    ADD COLUMN calendar_event_id varchar(255),
    ADD COLUMN meeting_url       varchar(500);

-- Held-but-absent is now COMPLETED with no attendance row.
UPDATE sessions SET booking_status = 'COMPLETED' WHERE booking_status = 'NO_SHOW';

ALTER TABLE sessions
    DROP CONSTRAINT ck_sessions_booking_status,
    DROP CONSTRAINT ck_sessions_booking_shape;

ALTER TABLE sessions
    ADD CONSTRAINT ck_sessions_booking_status
        CHECK (booking_status IS NULL
               OR booking_status IN ('UNSCHEDULED', 'SCHEDULED', 'COMPLETED')),
    -- A plain session is always dated and carries none of the booking columns.
    -- A task-backed row is UNSCHEDULED (materialised, undated) or dated; a
    -- cohort-wide row leaves member_id null (the whole cohort is expected).
    ADD CONSTRAINT ck_sessions_booking_shape CHECK (
        (program_task_id IS NULL AND member_id IS NULL AND coach_id IS NULL AND ends_at IS NULL
            AND booking_status IS NULL AND completed_at IS NULL AND session_date IS NOT NULL)
        OR (program_task_id IS NOT NULL AND booking_status IS NOT NULL AND (
               -- calendar_event_id is NOT constrained here: cancel reverts the
               -- row in one transaction and the calendar slice deletes the
               -- Google event and nulls the columns AFTER_COMMIT, so the id
               -- legitimately lingers on an UNSCHEDULED row for a moment.
               (booking_status = 'UNSCHEDULED' AND session_date IS NULL AND ends_at IS NULL
                   AND coach_id IS NULL AND completed_at IS NULL)
            OR (booking_status <> 'UNSCHEDULED' AND session_date IS NOT NULL
                   AND ends_at IS NOT NULL AND ends_at > session_date))));

-- One cohort-wide row per task; ux_sessions_task_member already covers 1:1 rows
-- (its (task, member) key admits many member_id NULLs, hence this second index).
CREATE UNIQUE INDEX ux_sessions_task_cohortwide ON sessions (program_task_id)
    WHERE program_task_id IS NOT NULL AND member_id IS NULL;

-- ex_sessions_coach_no_overlap stays as V215 wrote it: WHERE booking_status =
-- 'SCHEDULED', so UNSCHEDULED rows (null range) never enter the exclusion.

-- 3 ── the coach's connected calendar ------------------------------------------

-- One connection per coach. `provider` is the seam: a second provider is a new
-- CalendarProvider class and one more value in the check, nothing else.
CREATE TABLE coach_calendar_connections (
    user_id           uuid         NOT NULL,
    provider          varchar(20)  NOT NULL,
    account_email     varchar(320) NOT NULL,
    refresh_token_enc text         NOT NULL,
    calendar_id       varchar(255) NOT NULL DEFAULT 'primary',
    connected_at      timestamptz  NOT NULL DEFAULT now(),
    last_synced_at    timestamptz,
    last_error        text,

    CONSTRAINT pk_coach_calendar_connections PRIMARY KEY (user_id),
    CONSTRAINT fk_coach_calendar_connections_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_coach_calendar_connections_provider CHECK (provider IN ('GOOGLE'))
);
