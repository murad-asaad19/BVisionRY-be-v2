-- Create the initial Super Admin user
-- Password: admin123 (BCrypt hash)
-- Change this password immediately after first login in production

INSERT INTO users (id, email, name, password_hash, role, user_type, status, activated_at, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'admin@bvisionry.com',
    'Super Admin',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'SUPER_ADMIN',
    'LEADER',
    'ACTIVE',
    NOW(),
    NOW(),
    NOW()
)
ON CONFLICT (email) DO NOTHING;
