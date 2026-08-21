-- LIST cells move from ["a", "b"] to [{"id": …, "text": "a"}, …] so a comment
-- anchored to an entry survives the member reordering or deleting its siblings.
-- Comments follow: entry_index (positional, drifts) becomes entry_id (stable).

CREATE FUNCTION exercise_list_entries_with_ids(arr jsonb) RETURNS jsonb AS $$
    SELECT COALESCE(
        jsonb_agg(jsonb_build_object('id', gen_random_uuid()::text, 'text', e) ORDER BY ord),
        '[]'::jsonb)
    FROM jsonb_array_elements_text(arr) WITH ORDINALITY AS t(e, ord);
$$ LANGUAGE sql;

-- Rewrites every LIST cell of one cells object; other columns pass through.
CREATE FUNCTION exercise_cells_with_entry_ids(cells jsonb, template uuid) RETURNS jsonb AS $$
    SELECT COALESCE(jsonb_object_agg(kv.key,
        CASE WHEN jsonb_typeof(kv.value) = 'array'
              AND jsonb_typeof(kv.value -> 0) = 'string'
              AND EXISTS (SELECT 1 FROM exercise_columns c
                          WHERE c.template_id = template
                            AND c.type = 'LIST'
                            AND c.id::text = kv.key)
             THEN exercise_list_entries_with_ids(kv.value)
             ELSE kv.value END), '{}'::jsonb)
    FROM jsonb_each(cells) AS kv;
$$ LANGUAGE sql;

UPDATE exercise_rows r
SET cells = exercise_cells_with_entry_ids(r.cells, a.template_id)
FROM exercise_submissions s
JOIN exercise_assignments a ON a.id = s.assignment_id
WHERE s.id = r.submission_id
  AND r.cells IS NOT NULL;

UPDATE exercise_templates t
SET example_row = exercise_cells_with_entry_ids(t.example_row, t.id)
WHERE t.example_row IS NOT NULL;

UPDATE exercise_templates t
SET starter_rows = (
    SELECT jsonb_agg(exercise_cells_with_entry_ids(sr.obj, t.id) ORDER BY sr.ord)
    FROM jsonb_array_elements(t.starter_rows) WITH ORDINALITY AS sr(obj, ord))
WHERE t.starter_rows IS NOT NULL;

-- Re-anchor existing entry comments onto the id now sitting at their index.
ALTER TABLE exercise_comments ADD COLUMN entry_id TEXT;

UPDATE exercise_comments cm
SET entry_id = r.cells -> cm.column_id::text -> cm.entry_index ->> 'id'
FROM exercise_rows r
WHERE cm.entry_index IS NOT NULL
  AND cm.row_id = r.id
  AND cm.column_id IS NOT NULL;

ALTER TABLE exercise_comments DROP COLUMN entry_index;

DROP FUNCTION exercise_cells_with_entry_ids(jsonb, uuid);
DROP FUNCTION exercise_list_entries_with_ids(jsonb);
