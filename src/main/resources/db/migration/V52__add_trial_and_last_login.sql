-- V52__add_trial_and_last_login.sql
-- Trial: a Premium grant with an expiry. NULL or past = no active trial.
ALTER TABLE organizations
  ADD COLUMN trial_ends_at TIMESTAMP WITH TIME ZONE NULL;

CREATE INDEX idx_organizations_trial_ends_at
  ON organizations(trial_ends_at)
  WHERE trial_ends_at IS NOT NULL;

-- Last activity: updated on every successful login.
ALTER TABLE users
  ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE NULL;

CREATE INDEX idx_users_last_login_at ON users(last_login_at);
