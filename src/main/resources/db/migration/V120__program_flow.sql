-- =============================================================================
-- V100__program_flow.sql — LMS Program Flow (modules & tasks) + teams
-- =============================================================================
-- New `programflow` slice: cohort programs built as drip-scheduled modules of
-- multi-step form tasks, assigned to audiences (everyone / teams / members),
-- completed by learners with autosaved draft submissions. Gamification
-- (points/streaks/levels/leaderboard) is DERIVED from program_submissions —
-- points_awarded is written once at first submit so later due-date edits never
-- retro-change a learner's score.
--
-- Design decisions (mirrors the catalog-slice conventions, V76):
--   • Soft coupling to identity: FKs to organizations(id)/users(id) exist at
--     the DB level, but the Java slice references them by UUID only.
--   • teams are org-scoped; team_members has PRIMARY KEY (user_id) so a user
--     belongs to at most ONE team — moving a member is delete+insert.
--   • Field shape per type (question/options/items/accept/scale/…) lives in
--     program_task_fields.config JSONB (same pattern as questions.config_json).
--   • Learner answers are one JSONB map per (task, user) keyed by field id.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- teams — org-scoped grouping of members (cohort "Team Falcon" etc.)
-- -----------------------------------------------------------------------------
CREATE TABLE teams (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id      uuid         NOT NULL,
    name        varchar(120) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_teams PRIMARY KEY (id),
    CONSTRAINT fk_teams_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT uq_teams_org_name UNIQUE (org_id, name)
);

CREATE INDEX idx_teams_org ON teams (org_id);

CREATE TABLE team_members (
    user_id  uuid NOT NULL,
    team_id  uuid NOT NULL,

    -- one team per user
    CONSTRAINT pk_team_members PRIMARY KEY (user_id),
    CONSTRAINT fk_team_members_team FOREIGN KEY (team_id)
        REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_team_members_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_team_members_team ON team_members (team_id);

-- -----------------------------------------------------------------------------
-- program_settings — per-org program tweakables (stage label, drip, deadlines,
-- program end flag shown at the foot of the learner timeline / leaderboard).
-- -----------------------------------------------------------------------------
CREATE TABLE program_settings (
    org_id         uuid         NOT NULL,
    stage_label    varchar(30)  NOT NULL DEFAULT 'Week',
    drip_enabled   boolean      NOT NULL DEFAULT true,
    due_soon_days  integer      NOT NULL DEFAULT 3,
    end_label      varchar(120),
    end_at         timestamptz,

    CONSTRAINT pk_program_settings PRIMARY KEY (org_id),
    CONSTRAINT fk_program_settings_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_program_settings_due_soon CHECK (due_soon_days BETWEEN 1 AND 10)
);

-- -----------------------------------------------------------------------------
-- program_modules — kanban columns / journey stages (drip-scheduled)
-- -----------------------------------------------------------------------------
CREATE TABLE program_modules (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id       uuid         NOT NULL,
    name         varchar(200) NOT NULL,
    summary      text,
    position     integer      NOT NULL DEFAULT 0,
    lock_mode    varchar(20)  NOT NULL DEFAULT 'SEQUENTIAL',
    unlock_at    timestamptz,
    assign_mode  varchar(20)  NOT NULL DEFAULT 'ALL',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_program_modules PRIMARY KEY (id),
    CONSTRAINT fk_program_modules_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_program_modules_lock_mode
        CHECK (lock_mode IN ('UNLOCKED', 'SEQUENTIAL', 'SCHEDULED')),
    CONSTRAINT ck_program_modules_assign_mode
        CHECK (assign_mode IN ('ALL', 'TEAMS', 'MEMBERS'))
);

CREATE INDEX idx_program_modules_org ON program_modules (org_id, position);

CREATE TABLE program_module_teams (
    module_id  uuid NOT NULL,
    team_id    uuid NOT NULL,

    CONSTRAINT pk_program_module_teams PRIMARY KEY (module_id, team_id),
    CONSTRAINT fk_program_module_teams_module FOREIGN KEY (module_id)
        REFERENCES program_modules (id) ON DELETE CASCADE,
    CONSTRAINT fk_program_module_teams_team FOREIGN KEY (team_id)
        REFERENCES teams (id) ON DELETE CASCADE
);

CREATE TABLE program_module_members (
    module_id  uuid NOT NULL,
    user_id    uuid NOT NULL,

    CONSTRAINT pk_program_module_members PRIMARY KEY (module_id, user_id),
    CONSTRAINT fk_program_module_members_module FOREIGN KEY (module_id)
        REFERENCES program_modules (id) ON DELETE CASCADE,
    CONSTRAINT fk_program_module_members_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

-- -----------------------------------------------------------------------------
-- program_tasks — cards on the board; multi-step forms for learners.
-- DRAFT tasks (incl. AI drafts) are invisible to learners until published LIVE.
-- -----------------------------------------------------------------------------
CREATE TABLE program_tasks (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    module_id   uuid         NOT NULL,
    name        varchar(200) NOT NULL,
    due_date    date,
    status      varchar(20)  NOT NULL DEFAULT 'DRAFT',
    ai_draft    boolean      NOT NULL DEFAULT false,
    position    integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_program_tasks PRIMARY KEY (id),
    CONSTRAINT fk_program_tasks_module FOREIGN KEY (module_id)
        REFERENCES program_modules (id) ON DELETE CASCADE,
    CONSTRAINT ck_program_tasks_status CHECK (status IN ('DRAFT', 'LIVE'))
);

CREATE INDEX idx_program_tasks_module ON program_tasks (module_id, position);

-- -----------------------------------------------------------------------------
-- program_task_fields — one row per form field / step.
-- config JSONB per type: instructions{text} video{title,url} mcq{question,multi,
-- options[]} short/long{question,placeholder} file{question,accept}
-- checklist{question,items[]} rating{question,scale}
-- -----------------------------------------------------------------------------
CREATE TABLE program_task_fields (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    task_id     uuid        NOT NULL,
    field_type  varchar(20) NOT NULL,
    required    boolean     NOT NULL DEFAULT false,
    position    integer     NOT NULL DEFAULT 0,
    config      jsonb       NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT pk_program_task_fields PRIMARY KEY (id),
    CONSTRAINT fk_program_task_fields_task FOREIGN KEY (task_id)
        REFERENCES program_tasks (id) ON DELETE CASCADE,
    CONSTRAINT ck_program_task_fields_type CHECK (field_type IN
        ('INSTRUCTIONS', 'VIDEO', 'MCQ', 'SHORT', 'LONG', 'FILE', 'CHECKLIST', 'RATING'))
);

CREATE INDEX idx_program_task_fields_task ON program_task_fields (task_id, position);

-- -----------------------------------------------------------------------------
-- program_submissions — one row per (task, learner): autosaved draft answers,
-- then the submitted record. submitted_at / points_awarded are set once, on the
-- FIRST submit (learners may reopen and revise until the deadline).
-- -----------------------------------------------------------------------------
CREATE TABLE program_submissions (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    task_id         uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'DRAFT',
    answers         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    saved_at        timestamptz,
    submitted_at    timestamptz,
    points_awarded  integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_program_submissions PRIMARY KEY (id),
    CONSTRAINT fk_program_submissions_task FOREIGN KEY (task_id)
        REFERENCES program_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_program_submissions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_program_submissions_task_user UNIQUE (task_id, user_id),
    CONSTRAINT ck_program_submissions_status CHECK (status IN ('DRAFT', 'SUBMITTED'))
);

CREATE INDEX idx_program_submissions_user ON program_submissions (user_id);
