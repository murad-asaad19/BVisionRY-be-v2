-- Multi-select MULTIPLE_CHOICE answers join option labels with '|||', so the
-- stored value can easily exceed 255 characters when several long options are
-- picked. Lift the cap by promoting selected_value to TEXT (matches response_text).
ALTER TABLE answers
    ALTER COLUMN selected_value TYPE TEXT;
