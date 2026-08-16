-- Anchor a comment to one entry of a LIST cell. Null = the whole cell, as before.
ALTER TABLE exercise_comments
    ADD COLUMN entry_index INTEGER;
