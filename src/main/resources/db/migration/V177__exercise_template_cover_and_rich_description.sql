-- ============================================================================
-- Exercise templates carry the whole brief a member reads before they submit,
-- but `description` was plain TEXT rendered into a single <p> — every paragraph
-- break the author typed collapsed, so a 600-word brief reached the member as
-- one unbroken wall. It now holds a serialised tiptap document, the same shape
-- and the same TEXT column style as `content.body`.
--
-- Existing rows are plain text, so they are lifted into a document here rather
-- than left for the renderer to sniff at read time: one format in the column,
-- one code path out of it. Blank lines become empty paragraphs, which is what
-- the author meant by them. Rows that already start with '{' are left alone so
-- the migration is safe to re-run against a partly-converted table.
--
-- cover_image_url mirrors content.asset_url / courses.cover_image_url: it holds
-- either a `minio://bucket/key` marker resolved at read time, or an external URL.
-- ============================================================================

ALTER TABLE exercise_templates
    ADD COLUMN cover_image_url varchar(500);

UPDATE exercise_templates
SET description = (
    SELECT jsonb_build_object(
        'type', 'doc',
        'content', jsonb_agg(
            CASE
                WHEN line = '' THEN jsonb_build_object('type', 'paragraph')
                ELSE jsonb_build_object(
                    'type', 'paragraph',
                    'content', jsonb_build_array(
                        jsonb_build_object('type', 'text', 'text', line)))
            END
            ORDER BY ord)
    )::text
    FROM unnest(
        string_to_array(replace(description, E'\r\n', E'\n'), E'\n')
    ) WITH ORDINALITY AS lines(line, ord)
)
WHERE description IS NOT NULL
  AND btrim(description) <> ''
  AND left(btrim(description), 1) <> '{';
