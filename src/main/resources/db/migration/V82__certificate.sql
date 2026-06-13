-- =============================================================================
-- V82__certificate.sql — Certificates on course completion (#42)
-- =============================================================================
-- Stores a permanent, uniquely-numbered certificate for each completed
-- enrollment. Snapshots course_title and learner_name so the certificate
-- remains valid even after the underlying course or user record changes.
-- certificate_number pattern: BV-YYYY-XXXXXX (40-char max).
-- =============================================================================

CREATE TABLE certificate (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    enrollment_id      uuid          NOT NULL,
    user_id            uuid          NOT NULL,
    course_id          uuid          NOT NULL,
    certificate_number varchar(40)   NOT NULL,
    course_title       varchar(200)  NOT NULL,
    learner_name       varchar(200)  NOT NULL,
    issued_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_certificate PRIMARY KEY (id),
    CONSTRAINT uq_certificate_enrollment UNIQUE (enrollment_id),
    CONSTRAINT uq_certificate_number     UNIQUE (certificate_number),
    CONSTRAINT fk_certificate_enrollment FOREIGN KEY (enrollment_id)
        REFERENCES enrollment (id) ON DELETE CASCADE,
    CONSTRAINT fk_certificate_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_certificate_course FOREIGN KEY (course_id)
        REFERENCES course (id) ON DELETE CASCADE
);

CREATE INDEX ix_certificate_user        ON certificate (user_id);
CREATE INDEX ix_certificate_course      ON certificate (course_id);
CREATE INDEX ix_certificate_enrollment  ON certificate (enrollment_id);
CREATE INDEX ix_certificate_number      ON certificate (certificate_number);
CREATE INDEX ix_certificate_user_course ON certificate (user_id, course_id);
