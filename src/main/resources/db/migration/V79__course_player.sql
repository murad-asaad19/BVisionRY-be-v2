-- =============================================================================
-- V79__course_player.sql — Course Player, Enrollment & Progress
-- =============================================================================
-- 1. Extends content table with body (Tiptap JSON) + video_url + asset_url.
-- 2. Creates enrollments table.
-- 3. Creates content_progress table.
-- =============================================================================

-- Extend content with player-side columns
ALTER TABLE content
    ADD COLUMN IF NOT EXISTS body       text,
    ADD COLUMN IF NOT EXISTS video_url  varchar(500),
    ADD COLUMN IF NOT EXISTS asset_url  varchar(500);

-- Authoring columns on course / section / content (needed for write endpoints)
ALTER TABLE course
    ADD COLUMN IF NOT EXISTS cover_image_url varchar(500);

-- -------------------------------------------------------------------------
-- enrollment — tracks a user's enrolment in a course.
-- -------------------------------------------------------------------------
CREATE TABLE enrollment (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id       uuid         NOT NULL,
    course_id     uuid         NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    progress_pct  integer      NOT NULL DEFAULT 0,
    enrolled_at   timestamptz  NOT NULL DEFAULT now(),
    completed_at  timestamptz,

    CONSTRAINT pk_enrollment PRIMARY KEY (id),
    CONSTRAINT fk_enrollment_user   FOREIGN KEY (user_id)   REFERENCES users  (id) ON DELETE CASCADE,
    CONSTRAINT fk_enrollment_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT uq_enrollment_user_course UNIQUE (user_id, course_id),
    CONSTRAINT ck_enrollment_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'SUSPENDED', 'CANCELLED')),
    CONSTRAINT ck_enrollment_progress
        CHECK (progress_pct BETWEEN 0 AND 100)
);

CREATE INDEX ix_enrollment_user   ON enrollment (user_id);
CREATE INDEX ix_enrollment_course ON enrollment (course_id);

-- -------------------------------------------------------------------------
-- content_progress — tracks per-lesson completion within an enrollment.
-- -------------------------------------------------------------------------
CREATE TABLE content_progress (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    enrollment_id uuid         NOT NULL,
    content_id    uuid         NOT NULL,
    completed     boolean      NOT NULL DEFAULT false,
    completed_at  timestamptz,

    CONSTRAINT pk_content_progress PRIMARY KEY (id),
    CONSTRAINT fk_cp_enrollment FOREIGN KEY (enrollment_id)
        REFERENCES enrollment (id) ON DELETE CASCADE,
    CONSTRAINT fk_cp_content FOREIGN KEY (content_id)
        REFERENCES content (id) ON DELETE CASCADE,
    CONSTRAINT uq_cp_enrollment_content UNIQUE (enrollment_id, content_id)
);

CREATE INDEX ix_content_progress_enrollment ON content_progress (enrollment_id);
CREATE INDEX ix_content_progress_content    ON content_progress (content_id);
