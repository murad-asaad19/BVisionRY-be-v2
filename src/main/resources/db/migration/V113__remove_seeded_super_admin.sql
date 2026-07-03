-- Neutralize the seeded Super Admin credential.
--
-- V16__seed_super_admin.sql inserted admin@bvisionry.com as a SUPER_ADMIN /
-- ACTIVE account whose BCrypt password hash decodes to the well-known
-- plaintext "admin123". That row was later revoked by V84, but V84 was
-- deleted from source control (the sequence jumps V83 -> V85), so any
-- database that never applied V84 -- or any fresh database seeded by V16 --
-- still carries the live default credential. This migration is the durable
-- replacement for the deleted V84 and re-applies its intent permanently.
--
-- Strategy: UPDATE rather than DELETE, so audit / organization / foreign-key
-- rows that reference this user are left intact. The WHERE clause guards on
-- the exact V16-seeded hash, so an admin who has legitimately rotated the
-- password (any other hash) is never touched.
--
-- Defense in depth: we both (a) move the account to a non-ACTIVE status,
-- which JwtAuthenticationFilter rejects on every request, locking the account
-- immediately, and (b) overwrite the password hash with a non-BCrypt marker.
-- BCryptPasswordEncoder.matches() returns false for a malformed hash, so the
-- credential stays unusable even if status handling ever changes.
UPDATE users
SET status = 'DEACTIVATED',
    password_hash = '!revoked-seeded-credential-V113',
    updated_at = NOW()
WHERE email = 'admin@bvisionry.com'
  AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
