package com.bvisionry.comparison.repository;

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
     * @param content          the member's own submitted material, "label: value" per line
     * @param facilitatorNotes staff-authored feedback on that work. Kept SEPARATE
     *        because the prompt has to label it as facilitator-only — an approved
     *        narrative is member-visible, and mixing the two would leave the model
     *        no way to tell what it must never quote.
     */
    public record TaskActivity(UUID pillarId, UUID taskId, String taskName, String taskType,
                               LocalDate dueDate, boolean done, Instant completedAt,
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
     */
    public Map<UUID, List<TaskActivity>> activityByPillar(UUID cohortId, UUID userId) {
        MapSqlParameterSource params =
                new MapSqlParameterSource("cohortId", cohortId).addValue("userId", userId);
        List<TaskRow> tasks = jdbc.query("""
                SELECT ptp.pillar_id, t.id AS task_id, t.name, t.task_type, t.due_date,
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
                WHERE m.cohort_id = :cohortId AND t.status = 'LIVE'
                ORDER BY t.due_date NULLS LAST, t.position
                """.formatted(TaskCompletion.DONE_FOR_USER.formatted(":userId")),
                params,
                (rs, i) -> new TaskRow(rs.getObject("pillar_id", UUID.class),
                        rs.getObject("task_id", UUID.class), rs.getString("name"),
                        rs.getString("task_type"), date(rs), rs.getBoolean("done"),
                        instant(rs, "completed_at")));
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
                            t.dueDate(), t.done(), t.completedAt(),
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
     * EXERCISE: the member's grid. {@code cells} is keyed by column id (same
     * shape {@code ExerciseRowPayload} writes), so the column names come from
     * the assignment's own template — and each line names its row, because a
     * grid read as a flat list of values loses which answers belong together.
     */
    private List<Line> exerciseCells(Collection<UUID> taskIds, UUID userId) {
        return jdbc.query("""
                SELECT ea.program_task_id AS task_id,
                       'Row ' || (r.display_order + 1) || ' · ' || col.name AS label,
                       r.cells ->> (col.id::text) AS value
                FROM exercise_assignments ea
                JOIN exercise_submissions es ON es.assignment_id = ea.id
                JOIN exercise_rows r ON r.submission_id = es.id AND r.deleted_at IS NULL
                JOIN exercise_columns col ON col.template_id = ea.template_id
                WHERE ea.program_task_id IN (:taskIds) AND ea.user_id = :userId
                  AND r.cells ->> (col.id::text) IS NOT NULL
                ORDER BY ea.program_task_id, r.display_order, col.display_order
                """, params(taskIds, userId), Line::labelled);
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
     * <p>Banded into words rather than sent as a percentage. The narrative's one
     * hard rule is that it must never state a number, and handing the model
     * "IN_PROGRESS · 45%" is a temptation that buys nothing: the model cannot
     * repeat the figure, so all it can ever use is the band. The banding happens
     * in SQL, over a subquery, so {@link CourseProgressSql#LIVE_PROGRESS_PCT} is
     * still evaluated exactly once per row.
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
                           LocalDate dueDate, boolean done, Instant completedAt) {}

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
