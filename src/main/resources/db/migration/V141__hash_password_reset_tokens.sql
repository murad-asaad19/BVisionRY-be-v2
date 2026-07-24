-- V141__hash_password_reset_tokens.sql
-- Reset tokens are now stored as the SHA-256 hex of the raw UUID emailed to
-- the user, so a database read can't be turned into usable reset links.
-- Plaintext-era rows can never match a hash lookup — delete them outright
-- (they were short-lived anyway: 1h TTL).

DELETE FROM password_reset_tokens;

ALTER TABLE password_reset_tokens
    ALTER COLUMN token TYPE VARCHAR(64) USING token::text;
