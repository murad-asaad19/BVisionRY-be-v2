package com.bvisionry.comparison.repository;

import com.bvisionry.common.programaccess.ProgramAudience;
import com.bvisionry.common.programaccess.TaskCompletion;
import com.bvisionry.common.progress.CourseProgressSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the member actually DID on the programme work tagged to each pillar
 * (redesign spec §2's activity section), as raw SQL — same stance as its
 * sibling {@link ComparisonReadRepository}: the ArchUnit ratchet forbids new
 * feature→feature imports, so this class depends on the schema of the owning
 * slices (programflow, exercise, survey, enrollment) rather than importing
 * them.
 *
 * <p>Read once per founder, for every pillar at a time
 * ({@link #activityByPillar}): the narrative job loops one model call per
 * pillar, and firing five content queries inside that loop would multiply the
 * round-trips by the pillar count for no gain.
 *
 * <p><strong>No truncation, no cap</strong> (spec §2, deliberate): the model
 * sees the whole of what the founder wrote. Prompt-size limits are a listed
 * open item, not a thing to sneak in here.
 */
@Repository
public class NarrativeActivityRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NarrativeActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One tagged task and the member's work on it.
     *
     * @param aiContext        staff-written brief on what the task is for
     *        ({@code exercise_templates.ai_context}) — rendered as the ABOUT
     *        line; null for non-exercise tasks and unfilled templates
     * @param content          the member's own submitted material — for an
     *        exercise, a titles line plus one pipe-separated line per row
     * @param facilitatorNotes staff-authored feedback on that work. Kept SEPARATE
     *        because the prompt has to label it as facilitator-only — an approved
     *        narrative is member-visible, and mixing the two would leave the model
     *        no way to tell what it must never quote.
     */
    public record TaskActivity(UUID pillarId, UUID taskId, String taskName, String taskType,
                               String aiContext, LocalDate dueDate, boolean done, Instant completedAt,
                               List<String> content, List<String> facilitatorNotes) {

        /** Spec §2's three states; overdue is "past due and still not done". */
        public String status(LocalDate today) {
            if (done) {
                return "done";
            }
            return dueDate != null && dueDate.isBefore(today) ? "overdue" : "pending";
        }
    }

    /**
     * Every LIVE board task of the cohort that carries a pillar tag, keyed by
     * that (distance) pillar id, with the member's status and work on it.
     * DRAFT tasks are excluded for the same reason every other surface excludes
     * them: the member has never seen them, so there is nothing they did or
     * failed to do.
     *
     * <p>Scoped to the module's audience ({@link ProgramAudience#INCLUDES_USER},
     * as every other completion surface does): a MEMBERS module the founder is
     * not on was never assigned to them, so counting it would tell the model
     * they are overdue on work they were never given — and leak the task names
     * of a module they were deliberately excluded from.
     */
    public Map<UUID, List<TaskActivity>> activityByPillar(UUID cohortId, UUID userId) {
        MapSqlParameterSource params =
                new MapSqlParameterSource("cohortId", cohortId).addValue("userId", userId);
        List<TaskRow> tasks = jdbc.query("""
                SELECT ptp.pillar_id, t.id AS task_id, t.name, t.task_type, t.due_date,
                       tmpl.ai_context,
                       %1$s AS done,
                       (CASE t.task_type
                            WHEN 'LESSON' THEN (SELECT max(cps.submitted_at)
                                                FROM program_submissions cps
                                                WHERE cps.task_id = t.id AND cps.user_id = :userId)
                            WHEN 'COURSE' THEN (SELECT max(ce.completed_at) FROM enrollment ce
                                                WHERE ce.user_id = :userId AND ce.course_id = t.ref_id)
                            WHEN 'EXERCISE' THEN (SELECT max(ces.submitted_at)
                                                  FROM exercise_assignments cea
                                                  JOIN exercise_submissions ces
                                                       ON ces.assignment_id = cea.id
                                                  WHERE cea.program_task_id = t.id
                                                    AND cea.user_id = :userId)
                            WHEN 'SURVEY' THEN (SELECT max(csr.submitted_at) FROM survey_responses csr
                                                WHERE csr.program_task_id = t.id
                                                  AND csr.respondent_user_id = :userId)
                        END) AS completed_at
                FROM program_task_pillars ptp
                JOIN program_tasks t ON t.id = ptp.task_id
                JOIN program_modules m ON m.id = t.module_id
                LEFT JOIN exercise_templates tmpl
                       ON t.task_type = 'EXERCISE' AND tmpl.id = t.ref_id
                WHERE m.cohort_id = :cohortId AND t.status = 'LIVE'
                  AND %2$s
                ORDER BY t.due_date NULLS LAST, t.position
                """.formatted(TaskCompletion.DONE_FOR_USER.formatted(":userId"),
                        ProgramAudience.INCLUDES_USER.formatted(":userId")),
                params,
                (rs, i) -> new TaskRow(rs.getObject("pillar_id", UUID.class),
                        rs.getObject("task_id", UUID.class), rs.getString("name"),
                        rs.getString("task_type"), rs.getString("ai_context"),
                        date(rs), rs.getBoolean("done"), instant(rs, "completed_at")));
        if (tasks.isEmpty()) {
            return Map.of();
        }

        List<UUID> taskIds = tasks.stream().map(TaskRow::taskId).distinct().toList();
        Map<UUID, List<String>> content = new LinkedHashMap<>();
        Map<UUID, List<String>> notes = new LinkedHashMap<>();
        lessonAnswers(taskIds, userId).forEach(l -> add(content, l));
        exerciseCells(taskIds, userId).forEach(l -> add(content, l));
        surveyAnswers(taskIds, userId).forEach(l -> add(content, l));
        courseProgress(taskIds, userId).forEach(l -> add(content, l));
        exerciseComments(taskIds, userId).forEach(l -> add(l.facilitator() ? notes : content, l));

        Map<UUID, List<TaskActivity>> byPillar = new LinkedHashMap<>();
        for (TaskRow t : tasks) {
            byPillar.computeIfAbsent(t.pillarId(), k -> new ArrayList<>())
                    .add(new TaskActivity(t.pillarId(), t.taskId(), t.name(), t.taskType(),
                            t.aiContext(), t.dueDate(), t.done(), t.completedAt(),
                            content.getOrDefault(t.taskId(), List.of()),
                            notes.getOrDefault(t.taskId(), List.of())));
        }
        return byPillar;
    }

    /* ------------------------------------------------- per-type content reads */

    /**
     * LESSON: the answers map is keyed by field id, so the labels have to come
     * from {@code program_task_fields} — a bare answer value is unreadable
     * without the question that produced it.
     */
    private List<Line> lessonAnswers(Collection<UUID> taskIds, UUID userId) {
        return jdbc.query("""
                SELECT f.task_id,
                       COALESCE(f.config ->> 'question', f.config ->> 'title',
                                f.config ->> 'text', 'Answer') AS label,
                       ps.answers ->> (f.id::text) AS value
                FROM program_task_fields f
                JOIN program_submissions ps ON ps.task_id = f.task_id AND ps.user_id = :userId
                WHERE f.task_id IN (:taskIds) AND ps.answers ->> (f.id::text) IS NOT NULL
                ORDER BY f.task_id, f.position
                """, params(taskIds, userId), Line::labelled);
    }

    /**
     * EXERCISE: the member's grid as a TABLE, the way the sheet reads — one
     * "Row 1 (column titles)" header line per task, then one pipe-separated
     * line per data row (numbered from 2, matching the header being row 1).
     * The per-cell "Row N · column: value" rendering repeated every column
     * name once per cell, and reviewers read the header as if the founder had
     * written it.
     *
     * <p>Two cleanups happen per cell, both for the model's benefit:
     * a value that is a serialised JSON array/object (multi-select columns
     * store {@code [{"id","text"}]}) is flattened to its text parts — raw ids
     * in a prompt are noise the model can only mis-quote; and newlines/pipes
     * inside a value are collapsed so one grid row stays one physical line.
     *
     * <p>Rows the template seeded ({@code is_starter}) are marked
     * "(template-prefilled)" so the prompt can tell the model that content is
     * the exercise's scaffolding, not the founder's own words.
     */
    private List<Line> exerciseCells(Collection<UUID> taskIds, UUID userId) {
        record Cell(UUID taskId, int rowOrder, boolean starter,
                    String colName, int colOrder, String value) {}
        List<Cell> cells = jdbc.query("""
                SELECT ea.program_task_id AS task_id, r.display_order AS row_order,
                       r.is_starter, col.name AS col_name,
                       col.display_order AS col_order,
                       r.cells ->> (col.id::text) AS value
                FROM exercise_assignments ea
                JOIN exercise_submissions es ON es.assignment_id = ea.id
                JOIN exercise_rows r ON r.submission_id = es.id AND r.deleted_at IS NULL
                JOIN exercise_columns col ON col.template_id = ea.template_id
                WHERE ea.program_task_id IN (:taskIds) AND ea.user_id = :userId
                ORDER BY ea.program_task_id, r.display_order, col.display_order
                """, params(taskIds, userId),
                (rs, i) -> new Cell(rs.getObject("task_id", UUID.class), rs.getInt("row_order"),
                        rs.getBoolean("is_starter"), rs.getString("col_name"),
                        rs.getInt("col_order"), rs.getString("value")));

        List<Line> lines = new ArrayList<>();
        UUID task = null;
        Integer row = null;
        boolean starter = false;
        List<String> titles = new ArrayList<>();
        List<String> values = new ArrayList<>();
        String pendingHeader = null;
        for (int i = 0; i <= cells.size(); i++) {
            Cell cell = i < cells.size() ? cells.get(i) : null;
            boolean newRow = cell == null || !cell.taskId().equals(task)
                    || row == null || cell.rowOrder() != row;
            if (newRow && row != null && values.stream().anyMatch(v -> !"—".equals(v))) {
                // The header is HELD until a row with content earns it. A sheet
                // whose rows are all empty — the template seeded starter rows
                // and the member never filled them — must emit nothing at all,
                // or the task reads as attempted and the column titles read as
                // the founder's own words.
                if (pendingHeader != null) {
                    lines.add(new Line(task, pendingHeader, false));
                    pendingHeader = null;
                }
                lines.add(new Line(task, "Row " + (row + 2)
                        + (starter ? " (template-prefilled)" : "")
                        + ": " + String.join(" | ", values), false));
            }
            if (cell == null) {
                break;
            }
            if (!cell.taskId().equals(task)) {
                task = cell.taskId();
                titles = new ArrayList<>();
                pendingHeader = null;
                row = null;
            }
            if (row == null || cell.rowOrder() != row) {
                if (titles.isEmpty()) {
                    for (Cell c : cells.subList(i, cells.size())) {
                        if (!c.taskId().equals(task) || c.rowOrder() != cell.rowOrder()) {
                            break;
                        }
                        titles.add(c.colName());
                    }
                    pendingHeader = "Row 1 (column titles): " + String.join(" | ", titles);
                }
                row = cell.rowOrder();
                starter = cell.starter();
                values = new ArrayList<>();
            }
            values.add(flattenCell(cell.value()));
        }
        return lines;
    }

    /** Local mapper for flattening structured cell values — read-only, thread-safe. */
    private static final com.fasterxml.jackson.databind.ObjectMapper CELL_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * One cell as one table fragment: JSON arrays/objects flattened to their
     * "text" parts, newlines and pipes collapsed, empty → "—".
     */
    private static String flattenCell(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        String cleaned = value.strip();
        if (cleaned.startsWith("[") || cleaned.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = CELL_JSON.readTree(cleaned);
                List<String> parts = new ArrayList<>();
                collectText(node, parts);
                if (!parts.isEmpty()) {
                    cleaned = String.join(", ", parts);
                }
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                // Not JSON after all — a member's answer that happens to open
                // with a bracket is kept verbatim.
            }
        }
        return cleaned.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    /** Depth-first: "text" fields of option objects, plus bare scalars. */
    private static void collectText(com.fasterxml.jackson.databind.JsonNode node,
                                    List<String> out) {
        if (node.isArray()) {
            node.forEach(child -> collectText(child, out));
        } else if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode text = node.get("text");
            if (text != null && text.isTextual() && !text.asText().isBlank()) {
                out.add(text.asText());
            }
        } else if (node.isValueNode() && !node.asText().isBlank()) {
            out.add(node.asText());
        }
    }

    /**
     * EXERCISE review threads. A comment the MEMBER wrote is their own work and
     * reads as content; anything anyone else wrote is facilitator feedback the
     * prompt must label and the model must never quote (spec §2).
     */
    private List<Line> exerciseComments(Collection<UUID> taskIds, UUID userId) {
        return jdbc.query("""
                SELECT ea.program_task_id AS task_id, c.body, c.cell_value_snapshot,
                       (c.author_id <> :userId) AS facilitator
                FROM exercise_assignments ea
                JOIN exercise_submissions es ON es.assignment_id = ea.id
                JOIN exercise_comments c ON c.submission_id = es.id
                WHERE ea.program_task_id IN (:taskIds) AND ea.user_id = :userId
                ORDER BY ea.program_task_id, c.created_at
                """, params(taskIds, userId),
                (rs, i) -> {
                    String on = rs.getString("cell_value_snapshot");
                    return new Line(rs.getObject("task_id", UUID.class),
                            (on == null || on.isBlank() ? "" : "on \"" + on + "\": ")
                                    + rs.getString("body"),
                            rs.getBoolean("facilitator"));
                });
    }

    /** SURVEY: the question prompts and whatever the member answered them with. */
    private List<Line> surveyAnswers(Collection<UUID> taskIds, UUID userId) {
        return jdbc.query("""
                SELECT sr.program_task_id AS task_id, q.prompt_text AS label,
                       COALESCE(a.response_text, a.selected_value,
                                a.selected_values_json::text, a.numeric_value::text) AS value
                FROM survey_responses sr
                JOIN survey_answers a ON a.response_id = sr.id
                JOIN survey_questions q ON q.id = a.question_id
                WHERE sr.program_task_id IN (:taskIds) AND sr.respondent_user_id = :userId
                  AND COALESCE(a.response_text, a.selected_value,
                               a.selected_values_json::text, a.numeric_value::text) IS NOT NULL
                ORDER BY sr.program_task_id, q.display_order
                """, params(taskIds, userId), Line::labelled);
    }

    /**
     * COURSE: status and progress only — a course carries no submitted text, so
     * there is nothing else honest to hand the model
     * ({@link CourseProgressSql} owns the percentage, cached columns lie).
     *
     * <p>Banded into words rather than sent as a percentage — the band is all a
     * narrative has ever used of course progress. The banding happens in SQL,
     * over a subquery, so {@link CourseProgressSql#LIVE_PROGRESS_PCT} is still
     * evaluated exactly once per row.
     */
    private List<Line> courseProgress(Collection<UUID> taskIds, UUID userId) {
        return jdbc.query("""
                SELECT task_id, 'Course progress' AS label,
                       status || ' · ' || (CASE WHEN pct >= 100 THEN 'all lessons complete'
                                                WHEN pct >= 75 THEN 'most lessons complete'
                                                WHEN pct >= 50 THEN 'over half the lessons complete'
                                                WHEN pct > 0 THEN 'some lessons complete'
                                                ELSE 'not started' END) AS value
                FROM (SELECT t.id AS task_id, e.status, (%s) AS pct
                      FROM program_tasks t
                      JOIN enrollment e ON e.course_id = t.ref_id AND e.user_id = :userId
                                       AND e.status <> 'CANCELLED'
                      WHERE t.id IN (:taskIds)) progress
                """.formatted(CourseProgressSql.LIVE_PROGRESS_PCT),
                params(taskIds, userId), Line::labelled);
    }

    /* ---------------------------------------------------------------- helpers */

    private record TaskRow(UUID pillarId, UUID taskId, String name, String taskType,
                           String aiContext, LocalDate dueDate, boolean done,
                           Instant completedAt) {}

    /** One rendered line of a task's material, and which side of the wall it sits on. */
    private record Line(UUID taskId, String text, boolean facilitator) {

        static Line labelled(ResultSet rs, int i) throws SQLException {
            return new Line(rs.getObject("task_id", UUID.class),
                    rs.getString("label") + ": " + rs.getString("value"), false);
        }
    }

    private static void add(Map<UUID, List<String>> target, Line line) {
        target.computeIfAbsent(line.taskId(), k -> new ArrayList<>()).add(line.text());
    }

    private static MapSqlParameterSource params(Collection<UUID> taskIds, UUID userId) {
        return new MapSqlParameterSource("taskIds", taskIds).addValue("userId", userId);
    }

    private static LocalDate date(ResultSet rs) throws SQLException {
        java.sql.Date value = rs.getDate("due_date");
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
