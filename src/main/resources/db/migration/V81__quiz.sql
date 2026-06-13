-- =============================================================================
-- V81__quiz.sql — Quiz domain (author + take + auto-grade)
-- =============================================================================
-- Tables: quiz, quiz_question, quiz_option, quiz_attempt, quiz_attempt_answer.
-- quiz.content_id UNIQUE references content(id) (a QUIZ-type lesson, 1:1).
-- FK cascade: quiz→question→option; attempt→answer.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- quiz — configuration record for a QUIZ-type content lesson.
-- -----------------------------------------------------------------------------
CREATE TABLE quiz (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    content_id          uuid        NOT NULL,
    passing_score_pct   integer     NOT NULL DEFAULT 70,
    max_attempts        integer     NOT NULL DEFAULT 0,
    shuffle             boolean     NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_quiz                PRIMARY KEY (id),
    CONSTRAINT uq_quiz_content        UNIQUE (content_id),
    CONSTRAINT fk_quiz_content        FOREIGN KEY (content_id)
        REFERENCES content (id) ON DELETE CASCADE,
    CONSTRAINT ck_quiz_passing_score  CHECK (passing_score_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_quiz_max_attempts   CHECK (max_attempts >= 0)
);

CREATE INDEX ix_quiz_content ON quiz (content_id);

-- -----------------------------------------------------------------------------
-- quiz_question — one question within a quiz.
-- -----------------------------------------------------------------------------
CREATE TABLE quiz_question (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    quiz_id     uuid        NOT NULL,
    type        varchar(20) NOT NULL,
    prompt      text        NOT NULL,
    points      integer     NOT NULL DEFAULT 1,
    sequence    integer     NOT NULL DEFAULT 0,

    CONSTRAINT pk_quiz_question   PRIMARY KEY (id),
    CONSTRAINT fk_qq_quiz         FOREIGN KEY (quiz_id)
        REFERENCES quiz (id) ON DELETE CASCADE,
    CONSTRAINT ck_qq_type         CHECK (type IN ('SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE')),
    CONSTRAINT ck_qq_points       CHECK (points >= 0)
);

CREATE INDEX ix_quiz_question_quiz ON quiz_question (quiz_id);

-- -----------------------------------------------------------------------------
-- quiz_option — one selectable option within a question.
-- -----------------------------------------------------------------------------
CREATE TABLE quiz_option (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    question_id uuid        NOT NULL,
    text        varchar(500) NOT NULL,
    is_correct  boolean     NOT NULL DEFAULT false,
    sequence    integer     NOT NULL DEFAULT 0,

    CONSTRAINT pk_quiz_option       PRIMARY KEY (id),
    CONSTRAINT fk_qo_question       FOREIGN KEY (question_id)
        REFERENCES quiz_question (id) ON DELETE CASCADE
);

CREATE INDEX ix_quiz_option_question ON quiz_option (question_id);

-- -----------------------------------------------------------------------------
-- quiz_attempt — a graded submission attempt by a user.
-- -----------------------------------------------------------------------------
CREATE TABLE quiz_attempt (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    content_id      uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    enrollment_id   uuid        NOT NULL,
    score_pct       integer     NOT NULL,
    passed          boolean     NOT NULL,
    submitted_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_quiz_attempt      PRIMARY KEY (id),
    CONSTRAINT fk_qa_content        FOREIGN KEY (content_id)
        REFERENCES content (id) ON DELETE CASCADE,
    CONSTRAINT fk_qa_user           FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_qa_enrollment     FOREIGN KEY (enrollment_id)
        REFERENCES enrollment (id) ON DELETE CASCADE,
    CONSTRAINT ck_qa_score_pct      CHECK (score_pct BETWEEN 0 AND 100)
);

CREATE INDEX ix_quiz_attempt_content    ON quiz_attempt (content_id);
CREATE INDEX ix_quiz_attempt_user       ON quiz_attempt (user_id);
CREATE INDEX ix_quiz_attempt_enrollment ON quiz_attempt (enrollment_id);

-- -----------------------------------------------------------------------------
-- quiz_attempt_answer — per-option selections made in one attempt.
-- -----------------------------------------------------------------------------
CREATE TABLE quiz_attempt_answer (
    id          uuid NOT NULL DEFAULT gen_random_uuid(),
    attempt_id  uuid NOT NULL,
    question_id uuid NOT NULL,
    option_id   uuid NOT NULL,

    CONSTRAINT pk_quiz_attempt_answer   PRIMARY KEY (id),
    CONSTRAINT fk_qaa_attempt           FOREIGN KEY (attempt_id)
        REFERENCES quiz_attempt (id) ON DELETE CASCADE,
    CONSTRAINT fk_qaa_question          FOREIGN KEY (question_id)
        REFERENCES quiz_question (id) ON DELETE CASCADE,
    CONSTRAINT fk_qaa_option            FOREIGN KEY (option_id)
        REFERENCES quiz_option (id) ON DELETE CASCADE
);

CREATE INDEX ix_quiz_attempt_answer_attempt  ON quiz_attempt_answer (attempt_id);
CREATE INDEX ix_quiz_attempt_answer_question ON quiz_attempt_answer (question_id);
