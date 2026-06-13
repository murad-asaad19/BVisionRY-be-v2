-- =============================================================================
-- V85__catalog_certificate_schema_fixes.sql — Certificate FK / index fixes
-- =============================================================================
-- Corrects two issues introduced in V82__certificate.sql:
--
--   1. (HIGH) fk_certificate_user / fk_certificate_course were ON DELETE CASCADE
--      and fk_certificate_enrollment was ON DELETE CASCADE. A certificate is a
--      PERMANENT, uniquely-numbered record: V82 snapshots course_title and
--      learner_name precisely so it can outlive the source course/user/enrollment.
--      CASCADE silently destroys that permanent record whenever the referenced
--      user, course or enrollment is deleted — the opposite of the design intent.
--
--      Fix: treat a certificate as a standalone PERMANENT SNAPSHOT. The three FK
--      columns become NULLABLE and the FKs become ON DELETE SET NULL. When a
--      user / course / enrollment is hard-deleted, the certificate row SURVIVES
--      with its snapshot scalars intact (certificate_number, course_title,
--      learner_name, issued_at); only the no-longer-meaningful FK reference is
--      nulled. The public verify-by-number lookup continues to work because it
--      reads the snapshot columns, not the FKs.
--
--      Why SET NULL (not RESTRICT): RESTRICT would block every org/course/user
--      hard-delete that has any issued certificate (OrganizationService.hardDelete,
--      AuthoringService.deleteCourse), turning a permanent-record guarantee into a
--      latent FK-violation that rolls back the whole deletion. SET NULL keeps the
--      certificate permanent without coupling it to the lifecycle of its refs.
--
--   2. (LOW) Three indexes created in V82 are redundant:
--        • ix_certificate_enrollment — duplicates the implicit unique index
--          backing uq_certificate_enrollment (enrollment_id).
--        • ix_certificate_number     — duplicates the implicit unique index
--          backing uq_certificate_number (certificate_number).
--        • ix_certificate_user       — a left-prefix of the composite index
--          ix_certificate_user_course (user_id, course_id), so queries on
--          user_id alone are already served by the composite index.
--
-- A UNIQUE constraint over a now-nullable column is fine: Postgres treats NULLs
-- as distinct, so uq_certificate_enrollment still permits at most one certificate
-- per non-null enrollment_id while allowing many rows whose enrollment_id is NULL.
--
-- All DDL is idempotent (IF EXISTS) and Postgres-correct.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Relax the snapshot FK columns to NULLABLE so a deleted user/course/
--    enrollment can leave the certificate behind with a NULL reference.
-- -----------------------------------------------------------------------------
ALTER TABLE certificate ALTER COLUMN user_id       DROP NOT NULL;
ALTER TABLE certificate ALTER COLUMN course_id     DROP NOT NULL;
ALTER TABLE certificate ALTER COLUMN enrollment_id DROP NOT NULL;

-- -----------------------------------------------------------------------------
-- 2. Recreate all three FKs as ON DELETE SET NULL (was CASCADE in V82).
-- -----------------------------------------------------------------------------
ALTER TABLE certificate DROP CONSTRAINT IF EXISTS fk_certificate_user;
ALTER TABLE certificate
    ADD CONSTRAINT fk_certificate_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE certificate DROP CONSTRAINT IF EXISTS fk_certificate_course;
ALTER TABLE certificate
    ADD CONSTRAINT fk_certificate_course FOREIGN KEY (course_id)
        REFERENCES course (id) ON DELETE SET NULL;

ALTER TABLE certificate DROP CONSTRAINT IF EXISTS fk_certificate_enrollment;
ALTER TABLE certificate
    ADD CONSTRAINT fk_certificate_enrollment FOREIGN KEY (enrollment_id)
        REFERENCES enrollment (id) ON DELETE SET NULL;

-- -----------------------------------------------------------------------------
-- 3. Drop redundant indexes (kept: ix_certificate_course, ix_certificate_user_course).
-- -----------------------------------------------------------------------------
DROP INDEX IF EXISTS ix_certificate_enrollment;
DROP INDEX IF EXISTS ix_certificate_number;
DROP INDEX IF EXISTS ix_certificate_user;
