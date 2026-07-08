-- =============================================================================
-- V124__workshops.sql — Workshops (Control-Flip team exercises)
-- =============================================================================
-- New `workshops` slice: org-scoped workshops, each a sequential container of
-- card-based team exercises. An exercise is an ordered pipeline of tasks
-- (SORT / WEIGHT / TOP / QUESTION), each assigned to the team LEAD or the team
-- MEMBERs. The lead runs their tasks once per team; completing the last lead
-- task "shares" the run and unlocks member tasks, which members answer
-- individually. Analytics (time, attempts, role) are DERIVED from
-- workshop_task_submissions — no separate log table.
--
-- Design decisions (mirrors programflow, V120):
--   • Soft coupling to identity: DB-level FKs to organizations(id)/users(id),
--     Java slice references them by UUID only (native queries for user names).
--   • Teams are per-workshop (unlike org-wide `teams`): the same org can split
--     differently per workshop. One team per user per workshop; the admin
--     marks exactly one member per team as lead (partial unique index).
--   • Task shape per type (cards+answer key, labels, retryAfter, dealPerTeam,
--     topCount, prompt, steps, envelope copy, celebration) lives in
--     workshop_exercise_tasks.config JSONB. The answer key never leaves the
--     server; grading is server-side.
--   • workshop_exercise_runs is the per-(exercise, team) anchor: shared_at
--     marks the lead handoff; deals JSONB pins each sort task's randomly dealt,
--     side-balanced card subset so retries and downstream tasks see one hand.
--   • join_links gains a nullable workshop_id: joining through a workshop link
--     auto-assigns the new member to the least-filled team of that workshop.
-- =============================================================================

CREATE TABLE workshops (
    id                          uuid         NOT NULL DEFAULT gen_random_uuid(),
    org_id                      uuid         NOT NULL,
    name                        varchar(200) NOT NULL,
    position                    integer      NOT NULL DEFAULT 0,
    status                      varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    post_completion_survey_id   uuid,
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    updated_at                  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_workshops PRIMARY KEY (id),
    CONSTRAINT fk_workshops_org FOREIGN KEY (org_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_workshops_status CHECK (status IN ('ACTIVE', 'FINISHED'))
);

CREATE INDEX idx_workshops_org ON workshops (org_id, position);

-- -----------------------------------------------------------------------------
-- workshop_teams — per-workshop grouping; the lead flag lives on membership.
-- -----------------------------------------------------------------------------
CREATE TABLE workshop_teams (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    workshop_id  uuid         NOT NULL,
    name         varchar(120) NOT NULL,
    position     integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_workshop_teams PRIMARY KEY (id),
    CONSTRAINT fk_workshop_teams_workshop FOREIGN KEY (workshop_id)
        REFERENCES workshops (id) ON DELETE CASCADE,
    CONSTRAINT uq_workshop_teams_name UNIQUE (workshop_id, name)
);

CREATE INDEX idx_workshop_teams_workshop ON workshop_teams (workshop_id, position);

CREATE TABLE workshop_team_members (
    workshop_id  uuid    NOT NULL,
    user_id      uuid    NOT NULL,
    team_id      uuid    NOT NULL,
    is_lead      boolean NOT NULL DEFAULT false,
    joined_at    timestamptz NOT NULL DEFAULT now(),

    -- one team per user per workshop
    CONSTRAINT pk_workshop_team_members PRIMARY KEY (workshop_id, user_id),
    CONSTRAINT fk_workshop_team_members_workshop FOREIGN KEY (workshop_id)
        REFERENCES workshops (id) ON DELETE CASCADE,
    CONSTRAINT fk_workshop_team_members_team FOREIGN KEY (team_id)
        REFERENCES workshop_teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_workshop_team_members_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_workshop_team_members_team ON workshop_team_members (team_id);

-- exactly one lead per team
CREATE UNIQUE INDEX uq_workshop_team_lead
    ON workshop_team_members (team_id) WHERE is_lead;

-- -----------------------------------------------------------------------------
-- workshop_exercises — ordered container; teams progress through them
-- sequentially (exercise N+1 opens for a team once N is shared by its lead).
-- -----------------------------------------------------------------------------
CREATE TABLE workshop_exercises (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    workshop_id  uuid         NOT NULL,
    title        varchar(200) NOT NULL,
    position     integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_workshop_exercises PRIMARY KEY (id),
    CONSTRAINT fk_workshop_exercises_workshop FOREIGN KEY (workshop_id)
        REFERENCES workshops (id) ON DELETE CASCADE
);

CREATE INDEX idx_workshop_exercises_workshop ON workshop_exercises (workshop_id, position);

-- -----------------------------------------------------------------------------
-- workshop_exercise_tasks — the pipeline. Auto-wiring (weight ← nearest sort
-- above, top ← nearest weight, question ← nearest top) is derived from
-- position at read time, never stored.
-- config JSONB per type:
--   shared: steps, envTitle, envText, celebrate
--   sort:   instructions, leftLabel, rightLabel, retryAfter,
--           dealPerTeam (null = all), cards [{id, text, correct: left|right}]
--   top:    count
--   question: prompt
-- -----------------------------------------------------------------------------
CREATE TABLE workshop_exercise_tasks (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    exercise_id  uuid         NOT NULL,
    task_type    varchar(20)  NOT NULL,
    assignee     varchar(20)  NOT NULL DEFAULT 'LEAD',
    title        varchar(200) NOT NULL,
    position     integer      NOT NULL DEFAULT 0,
    config       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_workshop_exercise_tasks PRIMARY KEY (id),
    CONSTRAINT fk_workshop_exercise_tasks_exercise FOREIGN KEY (exercise_id)
        REFERENCES workshop_exercises (id) ON DELETE CASCADE,
    CONSTRAINT ck_workshop_exercise_tasks_type
        CHECK (task_type IN ('SORT', 'WEIGHT', 'TOP', 'QUESTION')),
    CONSTRAINT ck_workshop_exercise_tasks_assignee
        CHECK (assignee IN ('LEAD', 'MEMBER'))
);

CREATE INDEX idx_workshop_exercise_tasks_exercise
    ON workshop_exercise_tasks (exercise_id, position);

-- -----------------------------------------------------------------------------
-- workshop_exercise_runs — one row per (exercise, team), created when the
-- team's lead first starts the exercise. shared_at set when the last lead task
-- completes. deals: { taskId: [cardId, ...] } — the dealt hand per sort task.
-- -----------------------------------------------------------------------------
CREATE TABLE workshop_exercise_runs (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    exercise_id  uuid        NOT NULL,
    team_id      uuid        NOT NULL,
    shared_at    timestamptz,
    deals        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_workshop_exercise_runs PRIMARY KEY (id),
    CONSTRAINT fk_workshop_exercise_runs_exercise FOREIGN KEY (exercise_id)
        REFERENCES workshop_exercises (id) ON DELETE CASCADE,
    CONSTRAINT fk_workshop_exercise_runs_team FOREIGN KEY (team_id)
        REFERENCES workshop_teams (id) ON DELETE CASCADE,
    CONSTRAINT uq_workshop_exercise_runs UNIQUE (exercise_id, team_id)
);

-- -----------------------------------------------------------------------------
-- workshop_task_submissions — one row per performed task. Lead tasks: one row
-- per (task, team) with user_id = the lead. Member tasks: one row per
-- (task, team, user). payload per type:
--   sort:     { sorted: {cardId: left|right}, wrongIds: [cardId] }
--   weight:   { weights: {cardId: 0..100} }
--   top:      {}
--   question: { cardId, text }
-- started_at is set by "Start Task" (the count-up timer origin); completed_at
-- and elapsed_ms on completion. attempts counts graded sort attempts.
-- -----------------------------------------------------------------------------
CREATE TABLE workshop_task_submissions (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    task_id       uuid        NOT NULL,
    team_id       uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    attempts      integer     NOT NULL DEFAULT 0,
    started_at    timestamptz,
    completed_at  timestamptz,
    elapsed_ms    bigint,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_workshop_task_submissions PRIMARY KEY (id),
    CONSTRAINT fk_workshop_task_submissions_task FOREIGN KEY (task_id)
        REFERENCES workshop_exercise_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_workshop_task_submissions_team FOREIGN KEY (team_id)
        REFERENCES workshop_teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_workshop_task_submissions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_workshop_task_submissions UNIQUE (task_id, team_id, user_id)
);

CREATE INDEX idx_workshop_task_submissions_team ON workshop_task_submissions (team_id);
CREATE INDEX idx_workshop_task_submissions_user ON workshop_task_submissions (user_id);

-- -----------------------------------------------------------------------------
-- Workshop join links: an org join link may be bound to a workshop; accepting
-- it auto-assigns the new member to the least-filled team of that workshop.
-- -----------------------------------------------------------------------------
ALTER TABLE join_links
    ADD COLUMN workshop_id uuid
        REFERENCES workshops (id) ON DELETE SET NULL;

-- Re-scope "one active link per org" to the org-wide link only (workshop_id
-- IS NULL) and add "one active link per workshop", so a workshop link can
-- coexist with the org link and with other workshops' links.
DROP INDEX uq_join_links_active_org;
CREATE UNIQUE INDEX uq_join_links_active_org
    ON join_links (organization_id) WHERE is_active = true AND workshop_id IS NULL;
CREATE UNIQUE INDEX uq_join_links_active_workshop
    ON join_links (workshop_id) WHERE is_active = true AND workshop_id IS NOT NULL;
