-- V75__role_superset.sql
-- Widen the role taxonomy to the 5-role superset by ADDING INSTRUCTOR + MANAGER.
-- BVisionRY's SUPER_ADMIN / ORG_ADMIN / MEMBER names are kept (no rename), so all
-- live role data and every @PreAuthorize check remain valid.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('SUPER_ADMIN', 'ORG_ADMIN', 'INSTRUCTOR', 'MANAGER', 'MEMBER'));

-- Invitations may target any non-platform role.
ALTER TABLE invitations DROP CONSTRAINT IF EXISTS invitations_role_check;
ALTER TABLE invitations ADD CONSTRAINT invitations_role_check
    CHECK (role IN ('ORG_ADMIN', 'INSTRUCTOR', 'MANAGER', 'MEMBER'));
