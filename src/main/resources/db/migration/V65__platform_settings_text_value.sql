-- Extend platform_settings so the same table can hold non-integer values
-- (e.g. comma-separated email recipient lists for notification routing).
-- Existing rows keep using value_int; new string-typed settings use value_text.
-- A row uses exactly one of the two, enforced at the service layer rather than
-- at the DB level so we don't pay a check-constraint cost on every read.

ALTER TABLE platform_settings
    ALTER COLUMN value_int DROP NOT NULL,
    ADD COLUMN value_text TEXT;
