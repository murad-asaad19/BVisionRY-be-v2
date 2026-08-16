-- Every `minio://bucket/key` marker currently stored anywhere in the database.
--
-- WHY THIS IS DISCOVERED RATHER THAN LISTED. Media markers are persisted by
-- several features that do not know about each other — course covers, lesson
-- content, business-card portraits, organisation branding logos — and the set
-- grows whenever a feature gains an image. A hand-maintained list of columns is
-- a list that is wrong the first time somebody adds one, and it would be wrong
-- silently: the restore drill would report a clean bill of health while ignoring
-- the table that actually broke.
--
-- So this walks `information_schema` instead. It is slower and stranger than a
-- UNION of six known columns, and it is the version that still works in a year.
--
-- `query_to_xml` is the trick that makes it possible: PostgreSQL has no way to
-- select from a dynamically-named column in plain SQL, but it will run a
-- generated query and hand back the rows as XML, which `xpath` then unpacks.
-- `format` with %I quotes every identifier, so a column named `values` or a
-- table named `order` cannot break — or inject into — the generated statement.
--
-- Output: one row per stored marker, with where it came from.
SELECT
    source_table,
    source_column,
    marker
FROM (
    SELECT
        c.table_name  AS source_table,
        c.column_name AS source_column,
        (xpath(
            '//row/v/text()',
            query_to_xml(
                format(
                    'SELECT %I AS v FROM %I.%I WHERE %I LIKE ''minio://%%''',
                    c.column_name, c.table_schema, c.table_name, c.column_name
                ),
                false,  -- nulls: skip
                false,  -- tableforest: wrap rows in <table>
                ''
            )
        ))::text[] AS markers
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema
     AND t.table_name   = c.table_name
    WHERE c.table_schema = 'public'
      AND t.table_type   = 'BASE TABLE'
      -- Only column types that could hold a marker. Anything else cannot
      -- LIKE 'minio://%' and querying it would just cost time.
      AND c.data_type IN ('text', 'character varying')
) found
CROSS JOIN LATERAL unnest(found.markers) AS marker
ORDER BY source_table, source_column, marker;
