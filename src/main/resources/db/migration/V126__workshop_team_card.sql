-- Admin-selected team card (one of the 10 palette keys, e.g. 'red', 'blue';
-- may grow icon variants later). NULL = not chosen yet; the frontend falls
-- back to a palette card by team order.
ALTER TABLE workshop_teams ADD COLUMN card varchar(24);
