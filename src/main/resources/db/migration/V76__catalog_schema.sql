-- =============================================================================
-- V76__catalog_schema.sql — Bvisionry LMS catalog schema
-- =============================================================================
-- Creates the catalog-domain tables: course, section, content, tag, course_tag,
-- review, and course_outcome.
--
-- Design decisions:
--   • course.org_id REFERENCES organizations(id)  — catalog courses belong to an org.
--   • course.instructor_id REFERENCES users(id)   — nullable FK; ON DELETE SET NULL.
--   • course.instructor_name / instructor_title / instructor_bio — denormalized from
--     users at authoring time so the public catalog list requires no join to users.
--   • section.org_id / content.org_id / review.org_id / tag.org_id are denormalized
--     for potential future RLS; they carry a redundant org check that mirrors the
--     parent course's org_id.
--   • pgcrypto is assumed already installed (V1__create_organizations.sql uses
--     gen_random_uuid(); pgcrypto was installed in the OLD learn schema and is a
--     standard Postgres extension — if missing, run: CREATE EXTENSION pgcrypto;).
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- -----------------------------------------------------------------------------
-- course — the catalog unit.
-- FK to organizations(id) and users(id) which already exist in this DB.
-- -----------------------------------------------------------------------------
CREATE TABLE course (
    id                        uuid          NOT NULL DEFAULT gen_random_uuid(),
    org_id                    uuid          NOT NULL,
    slug                      varchar(160)  NOT NULL,
    title                     varchar(200)  NOT NULL,
    subtitle                  varchar(300),
    category                  varchar(80),
    level                     varchar(20)   NOT NULL DEFAULT 'BEGINNER',
    mode                      varchar(20)   NOT NULL DEFAULT 'SELF_PACED',
    audience                  varchar(20)   NOT NULL DEFAULT 'PUBLIC',
    access                    varchar(20)   NOT NULL DEFAULT 'EVERYONE',
    description               text,
    price                     numeric(10,2),
    currency                  varchar(3)    NOT NULL DEFAULT 'USD',
    rating                    numeric(2,1)  NOT NULL DEFAULT 0,
    reviews_count             integer       NOT NULL DEFAULT 0,
    learners_count            integer       NOT NULL DEFAULT 0,
    duration_hours            numeric(6,2),
    lessons_count             integer       NOT NULL DEFAULT 0,
    certification_title       varchar(200),
    certification_passing_pct integer,
    instructor_id             uuid,
    instructor_name           varchar(160),
    instructor_title          varchar(200),
    instructor_bio            text,
    enroll_policy             varchar(20)   NOT NULL DEFAULT 'OPEN',
    visibility                varchar(20)   NOT NULL DEFAULT 'PUBLIC',
    cover_gradient            varchar(120),
    state                     varchar(20)   NOT NULL DEFAULT 'PUBLISHED',
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_course PRIMARY KEY (id),
    CONSTRAINT fk_course_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_instructor FOREIGN KEY (instructor_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uq_course_slug UNIQUE (slug),
    CONSTRAINT ck_course_level
        CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT', 'ALL_LEVELS')),
    CONSTRAINT ck_course_mode
        CHECK (mode IN ('SELF_PACED', 'INSTRUCTOR_LED', 'BLENDED', 'COHORT', 'LIVE')),
    CONSTRAINT ck_course_audience
        CHECK (audience IN ('EMPLOYEE', 'PUBLIC', 'B2B')),
    CONSTRAINT ck_course_access
        CHECK (access IN ('EVERYONE', 'SIGNED_IN', 'ENROLLED', 'LINK')),
    CONSTRAINT ck_course_enroll_policy
        CHECK (enroll_policy IN ('OPEN', 'INVITATION', 'REQUEST', 'PAYMENT', 'MANUAL')),
    CONSTRAINT ck_course_visibility
        CHECK (visibility IN ('PUBLIC', 'UNLISTED', 'PRIVATE', 'MEMBERS')),
    CONSTRAINT ck_course_state
        CHECK (state IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_course_rating
        CHECK (rating >= 0 AND rating <= 5),
    CONSTRAINT ck_course_price
        CHECK (price IS NULL OR price >= 0),
    CONSTRAINT ck_course_cert_pct
        CHECK (certification_passing_pct IS NULL
               OR (certification_passing_pct BETWEEN 0 AND 100))
);

CREATE INDEX ix_course_org        ON course (org_id);
CREATE INDEX ix_course_slug       ON course (slug);
CREATE INDEX ix_course_state      ON course (state);
CREATE INDEX ix_course_category   ON course (category);
CREATE INDEX ix_course_level      ON course (level);
CREATE INDEX ix_course_audience   ON course (audience);

-- -----------------------------------------------------------------------------
-- section — an ordered grouping of contents within a course.
-- -----------------------------------------------------------------------------
CREATE TABLE section (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id      uuid         NOT NULL,
    course_id   uuid         NOT NULL,
    title       varchar(200) NOT NULL,
    sequence    integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_section PRIMARY KEY (id),
    CONSTRAINT fk_section_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_section_course FOREIGN KEY (course_id)
        REFERENCES course (id) ON DELETE CASCADE
);

CREATE INDEX ix_section_course ON section (course_id, sequence);
CREATE INDEX ix_section_org    ON section (org_id);

-- -----------------------------------------------------------------------------
-- content — an ordered learning item within a section.
-- Includes the full §10 lesson-type vocabulary in the CHECK constraint.
-- -----------------------------------------------------------------------------
CREATE TABLE content (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id         uuid         NOT NULL,
    section_id     uuid         NOT NULL,
    title          varchar(200) NOT NULL,
    content_type   varchar(20)  NOT NULL DEFAULT 'VIDEO',
    duration_min   integer,
    allow_preview  boolean      NOT NULL DEFAULT false,
    sequence       integer      NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_content PRIMARY KEY (id),
    CONSTRAINT fk_content_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_section FOREIGN KEY (section_id)
        REFERENCES section (id) ON DELETE CASCADE,
    CONSTRAINT ck_content_type
        CHECK (content_type IN (
            'VIDEO', 'ARTICLE', 'QUIZ', 'DOCUMENT', 'SCORM', 'ASSIGNMENT', 'WEBPAGE',
            'PDF', 'CERTIFICATION', 'PAGE', 'LINK', 'IMAGE'
        )),
    CONSTRAINT ck_content_duration
        CHECK (duration_min IS NULL OR duration_min >= 0)
);

CREATE INDEX ix_content_section ON content (section_id, sequence);
CREATE INDEX ix_content_org     ON content (org_id);

-- -----------------------------------------------------------------------------
-- tag — a free-form label, unique per org.
-- -----------------------------------------------------------------------------
CREATE TABLE tag (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id      uuid         NOT NULL,
    name        varchar(80)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_tag PRIMARY KEY (id),
    CONSTRAINT fk_tag_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT uq_tag_org_name UNIQUE (org_id, name)
);

CREATE INDEX ix_tag_org ON tag (org_id);

-- -----------------------------------------------------------------------------
-- course_tag — many-to-many join between course and tag.
-- -----------------------------------------------------------------------------
CREATE TABLE course_tag (
    course_id  uuid NOT NULL,
    tag_id     uuid NOT NULL,

    CONSTRAINT pk_course_tag PRIMARY KEY (course_id, tag_id),
    CONSTRAINT fk_course_tag_course FOREIGN KEY (course_id)
        REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_tag_tag FOREIGN KEY (tag_id)
        REFERENCES tag (id) ON DELETE CASCADE
);

CREATE INDEX ix_course_tag_tag ON course_tag (tag_id);

-- -----------------------------------------------------------------------------
-- review — a learner's rating + optional comment on a course.
-- -----------------------------------------------------------------------------
CREATE TABLE review (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id       uuid         NOT NULL,
    course_id    uuid         NOT NULL,
    author_name  varchar(160) NOT NULL,
    rating       integer      NOT NULL,
    comment      text,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_review PRIMARY KEY (id),
    CONSTRAINT fk_review_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_course FOREIGN KEY (course_id)
        REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT ck_review_rating
        CHECK (rating >= 1 AND rating <= 5)
);

CREATE INDEX ix_review_course ON review (course_id);
CREATE INDEX ix_review_org    ON review (org_id);

-- -----------------------------------------------------------------------------
-- course_outcome — ordered list of learning outcomes per course.
-- -----------------------------------------------------------------------------
CREATE TABLE course_outcome (
    course_id  uuid         NOT NULL,
    sequence   integer      NOT NULL,
    outcome    varchar(300) NOT NULL,

    CONSTRAINT pk_course_outcome PRIMARY KEY (course_id, sequence),
    CONSTRAINT fk_course_outcome_course FOREIGN KEY (course_id)
        REFERENCES course (id) ON DELETE CASCADE
);
