-- LIST column type: multiple free-text entries in one cell (JSON string array).
ALTER TABLE exercise_columns
    DROP CONSTRAINT exercise_columns_type_check;
ALTER TABLE exercise_columns
    ADD CONSTRAINT exercise_columns_type_check
    CHECK (type IN ('TEXT', 'LONG_TEXT', 'NUMBER', 'DATE', 'SELECT', 'LIST'));
