-- Remove the hardcoded SUPER_ADMIN seeded by V16 (email admin@bvisionry.com,
-- password 'admin123'). The credential was committed to source control and is
-- considered compromised. A super admin is now provisioned at startup from
-- configuration (SuperAdminBootstrap) instead of being baked into a migration.
--
-- We delete ONLY if the password hash is still the known-compromised bcrypt
-- value. If an operator has already rotated the password, the WHERE clause
-- no longer matches and the legitimate admin row is preserved.
DELETE FROM users
WHERE email = 'admin@bvisionry.com'
  AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
