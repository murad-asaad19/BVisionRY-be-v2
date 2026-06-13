-- Member types were previously a hardcoded Java enum (FOUNDER, LEADER). This
-- migration moves them to a managed table so super admins can add new types
-- without a code change.

CREATE TABLE member_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    label VARCHAR(128) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_member_types_display_order ON member_types (display_order);

-- Seed the two system types that existed as enum values. is_system = TRUE
-- protects them from deletion. The codes match the old enum names so existing
-- users.user_type values continue to resolve.
INSERT INTO member_types (code, label, display_order, is_system)
VALUES ('LEADER', 'Leader', 0, TRUE),
       ('FOUNDER', 'Founder', 1, TRUE);

-- The old column was VARCHAR(20) which is tight for arbitrary admin-defined
-- codes. Widen it to match member_types.code and drop the LEADER default —
-- we want the application to decide the default going forward.
ALTER TABLE users ALTER COLUMN user_type TYPE VARCHAR(64);
ALTER TABLE users ALTER COLUMN user_type DROP DEFAULT;
