package com.bvisionry.programflow.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.bvisionry.programflow.dto.FieldUpsert;
import com.bvisionry.programflow.dto.SaveBoardRequest.ModuleUpsert;
import com.bvisionry.programflow.dto.SaveBoardRequest.TaskUpsert;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes a cohort's curriculum equal the normalised {@code ModuleUpsert} list of
 * a {@code SaveBoardRequest}, in raw SQL — the engine behind the Curriculum
 * builder's whole-board Save, where the board is what the admin edited locally
 * (positions implied by list order and re-derived on write; the service mints
 * ids for new fields BEFORE handing the board here, so the probe, the foreign-id
 * guard and the restore all see the same ids).
 *
 * <p><strong>Why not JPA.</strong> The write has to create rows <em>under ids
 * the browser minted</em>, and to re-create a row it was handed by id — that is
 * what keeps member work attached, since it points at a task by bare uuid with
 * no FK ({@code submissions.program_task_id},
 * {@code exercise_assignments.program_task_id},
 * {@code survey_responses.program_task_id}). Every program-flow entity declares
 * its id {@code insertable = false} with a {@code gen_random_uuid()} default, so
 * JPA structurally cannot insert one with a chosen id. Rather than a hybrid that
 * fights orphan-removal and flush ordering, the whole write is one deterministic
 * SQL sequence — the same stance as {@link TaskSpineRepository}.
 *
 * <p><strong>Order is the correctness.</strong> Modules and tasks are upserted
 * BEFORE anything is deleted, so a task the admin dragged into a module the same
 * payload creates is moved home first and never gets caught by the old module's
 * {@code ON DELETE CASCADE} — which would have taken its
 * {@code program_submissions} with it.
 */
@Repository
public class BoardRestoreRepository {

    /**
     * ponytail: a nil-uuid sentinel so an EMPTY snapshot still binds
     * {@code NOT IN (:ids)} — the JDBC template cannot bind an empty list, and
     * no real row carries the nil uuid.
     */
    private static final UUID NONE = new UUID(0L, 0L);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public BoardRestoreRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /* ------------------------------------------------------- member-work probe */

    /** A task this write would delete, and how many members have work against it. */
    public record DoomedTask(String taskName, long memberCount) {}

    /**
     * The tasks this write would DESTROY MEMBER WORK ON, with the number of
     * distinct members behind it: tasks the snapshot does not hold (a delete),
     * plus kept tasks the snapshot RETYPES — {@code task_type} decides how
     * completion is judged and which fields survive, so turning a LESSON with
     * submissions into a COURSE drops its fields and silently reverts every
     * member's progress, exactly as deleting it would. Both take the same 409
     * and the same {@code force}. Every place work can hang off a task is
     * counted: the LESSON submission table plus the three bare-uuid tags —
     * assessment ({@code submissions}, V164), exercise assignment and survey
     * response (both V173) — and the session booking (V215/V216).
     */
    public List<DoomedTask> memberWorkAtRisk(UUID cohortId, List<ModuleUpsert> board) {
        return jdbc.query("""
                SELECT t.name AS task_name, w.member_count
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                CROSS JOIN LATERAL (
                    SELECT count(DISTINCT x.user_id) AS member_count
                    FROM (SELECT ps.user_id FROM program_submissions ps WHERE ps.task_id = t.id
                          UNION
                          SELECT s.user_id FROM submissions s WHERE s.program_task_id = t.id
                          UNION
                          SELECT ea.user_id FROM exercise_assignments ea
                           WHERE ea.program_task_id = t.id
                          UNION
                          SELECT sr.respondent_user_id FROM survey_responses sr
                           WHERE sr.program_task_id = t.id
                          UNION
                          -- Sessions spec v2: a DATED session is member work —
                          -- their own 1:1 booking, or their attendance row on
                          -- the cohort-wide one. UNSCHEDULED rows are
                          -- materialised by the platform, not earned by a
                          -- member, so they must never make a task undeletable.
                          SELECT coalesce(cs.member_id, csa.member_id)
                            FROM sessions cs
                            LEFT JOIN session_attendance csa
                                   ON csa.session_id = cs.id AND cs.member_id IS NULL
                           WHERE cs.program_task_id = t.id
                             AND cs.booking_status <> 'UNSCHEDULED') x
                ) w
                WHERE m.cohort_id = :cohortId
                  AND (t.id NOT IN (:keptTaskIds) OR t.id IN (:retypedIds))
                  AND w.member_count > 0
                ORDER BY t.name
                """,
                new MapSqlParameterSource("cohortId", cohortId)
                        .addValue("keptTaskIds", withSentinel(taskIds(board)))
                        .addValue("retypedIds", withSentinel(retypedTaskIds(board))),
                (rs, i) -> new DoomedTask(rs.getString("task_name"), rs.getLong("member_count")));
    }

    /**
     * The four task-work tables of {@link #memberWorkAtRisk} as one
     * {@code (task_id, user_id)} relation — the single place to add the next
     * work-bearing table so the delete guard and the switcher badge cannot
     * disagree. UNION ALL: the consumers wrap it in {@code count(DISTINCT …)},
     * so a dedup pass here would be pure waste.
     */
    private static final String TASK_WORK = """
            SELECT ps.task_id, ps.user_id FROM program_submissions ps
            UNION ALL
            SELECT s.program_task_id, s.user_id FROM submissions s
             WHERE s.program_task_id IS NOT NULL
            UNION ALL
            SELECT ea.program_task_id, ea.user_id FROM exercise_assignments ea
             WHERE ea.program_task_id IS NOT NULL AND ea.user_id IS NOT NULL
            UNION ALL
            SELECT sr.program_task_id, sr.respondent_user_id FROM survey_responses sr
             WHERE sr.program_task_id IS NOT NULL""";

    /**
     * Distinct members with ANY work in this cohort — the {@link #TASK_WORK}
     * tables plus session attendance (V163, cohort-cascaded like the rest).
     * Drives the cohort delete guard: > 0 means the cohort cannot be deleted.
     */
    public long memberWorkCount(UUID cohortId) {
        Long n = jdbc.queryForObject("""
                SELECT count(DISTINCT y.user_id) FROM (
                    SELECT x.user_id FROM (""" + TASK_WORK + """
                    ) x(task_id, user_id)
                    JOIN program_tasks t ON t.id = x.task_id
                    JOIN program_modules m ON m.id = t.module_id
                    WHERE m.cohort_id = :cohortId
                    UNION ALL
                    SELECT sa.member_id FROM session_attendance sa
                    JOIN sessions se ON se.id = sa.session_id
                    WHERE se.cohort_id = :cohortId
                ) y(user_id)
                """,
                new MapSqlParameterSource("cohortId", cohortId), Long.class);
        return n == null ? 0 : n;
    }

    /** Batch twin of {@link #memberWorkCount} for the cohort switcher list. */
    public Map<UUID, Long> memberWorkCountByCohort() {
        Map<UUID, Long> out = new HashMap<>();
        jdbc.query("""
                SELECT y.cohort_id, count(DISTINCT y.user_id) AS member_count FROM (
                    SELECT m.cohort_id, x.user_id FROM (""" + TASK_WORK + """
                    ) x(task_id, user_id)
                    JOIN program_tasks t ON t.id = x.task_id
                    JOIN program_modules m ON m.id = t.module_id
                    UNION ALL
                    SELECT se.cohort_id, sa.member_id FROM session_attendance sa
                    JOIN sessions se ON se.id = sa.session_id
                ) y(cohort_id, user_id)
                GROUP BY y.cohort_id
                """,
                rs -> {
                    out.put(rs.getObject("cohort_id", UUID.class), rs.getLong("member_count"));
                });
        return out;
    }

    /**
     * The snapshot tasks whose stored SHAPE differs from the incoming one. A
     * snapshot id with no stored row is a create, not a retype, and simply
     * doesn't come back from the query.
     */
    private List<UUID> retypedTaskIds(List<ModuleUpsert> board) {
        Map<UUID, String> incoming = new HashMap<>();
        for (ModuleUpsert m : board) {
            for (TaskUpsert t : m.tasks()) {
                incoming.put(t.id(), taskShape(t.taskType().name(),
                        t.sessionType() == null ? null : t.sessionType().name()));
            }
        }
        return jdbc.query("""
                SELECT id, task_type, session_type FROM program_tasks WHERE id IN (:ids)
                """,
                new MapSqlParameterSource("ids", withSentinel(incoming.keySet())),
                (rs, i) -> Map.entry(rs.getObject("id", UUID.class),
                        taskShape(rs.getString("task_type"), rs.getString("session_type"))))
                .stream()
                .filter(e -> !e.getValue().equals(incoming.get(e.getKey())))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * What decides how a task's member work is judged: the type, plus a SESSION
     * task's subtype — switching a 1:1 to a group session re-homes every
     * booking exactly the way a retype re-homes a submission (sessions spec v2
     * §3.1), so it takes the same 409.
     */
    private static String taskShape(String taskType, String sessionType) {
        return taskType + "/" + sessionType;
    }

    /**
     * The payload ids that already exist under a DIFFERENT cohort. Every write
     * here upserts BY ID, so without this guard an admin could pull another
     * cohort's module, task or field into theirs by naming its id.
     */
    public List<UUID> foreignIds(UUID cohortId, Collection<UUID> moduleIds,
            Collection<UUID> taskIds, Collection<UUID> fieldIds) {
        return jdbc.query("""
                SELECT m.id FROM program_modules m
                 WHERE m.id IN (:moduleIds) AND m.cohort_id <> :cohortId
                UNION ALL
                SELECT t.id FROM program_tasks t
                  JOIN program_modules m ON m.id = t.module_id
                 WHERE t.id IN (:taskIds) AND m.cohort_id <> :cohortId
                UNION ALL
                SELECT f.id FROM program_task_fields f
                  JOIN program_tasks t ON t.id = f.task_id
                  JOIN program_modules m ON m.id = t.module_id
                 WHERE f.id IN (:fieldIds) AND m.cohort_id <> :cohortId
                """,
                new MapSqlParameterSource("cohortId", cohortId)
                        .addValue("moduleIds", withSentinel(moduleIds))
                        .addValue("taskIds", withSentinel(taskIds))
                        .addValue("fieldIds", withSentinel(fieldIds)),
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    /* -------------------------------------------------------------- the restore */

    /**
     * Makes the cohort's curriculum match {@code snapshot}: surviving rows are
     * updated, rows deleted since are re-created under their original ids, rows
     * created since are deleted. Positions are re-derived from list order, so
     * the restored board has no gaps.
     */
    public void restore(UUID cohortId, List<ModuleUpsert> board) {
        upsertModules(cohortId, board);
        upsertTasks(board);
        upsertFields(board);
        // Only now is it safe to delete: every kept row sits under its
        // payload parent, so no cascade can reach one.
        deleteTasksNotIn(cohortId, taskIds(board));
        deleteModulesNotIn(cohortId, moduleIds(board));
        deleteFieldsNotIn(cohortId, fieldIds(board));
        replaceAudiences(board);
        replacePillarTags(board);
    }

    private void upsertModules(UUID cohortId, List<ModuleUpsert> board) {
        List<SqlParameterSource> batch = new ArrayList<>();
        for (int i = 0; i < board.size(); i++) {
            ModuleUpsert m = board.get(i);
            batch.add(new MapSqlParameterSource("id", m.id())
                    .addValue("cohortId", cohortId)
                    .addValue("name", m.name())
                    .addValue("summary", m.summary())
                    .addValue("pillarLabel", m.pillarLabel())
                    .addValue("paced", m.paced())
                    .addValue("position", i)
                    .addValue("lockMode", m.lockMode().name())
                    .addValue("unlockAt", m.unlockAt())
                    .addValue("assignMode", m.assignMode().name()));
        }
        batchUpdate("""
                INSERT INTO program_modules (id, cohort_id, name, summary, pillar_label,
                                             paced, position, lock_mode, unlock_at, assign_mode)
                VALUES (:id, :cohortId, :name, :summary, :pillarLabel,
                        :paced, :position, :lockMode, :unlockAt, :assignMode)
                ON CONFLICT (id) DO UPDATE SET
                    name         = EXCLUDED.name,
                    summary      = EXCLUDED.summary,
                    pillar_label = EXCLUDED.pillar_label,
                    paced        = EXCLUDED.paced,
                    position     = EXCLUDED.position,
                    lock_mode    = EXCLUDED.lock_mode,
                    unlock_at    = EXCLUDED.unlock_at,
                    assign_mode  = EXCLUDED.assign_mode,
                    updated_at   = now()
                """, batch);
    }

    private void upsertTasks(List<ModuleUpsert> board) {
        List<SqlParameterSource> batch = new ArrayList<>();
        for (ModuleUpsert m : board) {
            List<TaskUpsert> tasks = m.tasks();
            for (int i = 0; i < tasks.size(); i++) {
                TaskUpsert t = tasks.get(i);
                batch.add(new MapSqlParameterSource("id", t.id())
                        .addValue("moduleId", m.id())
                        .addValue("name", t.name())
                        .addValue("taskType", t.taskType().name())
                        .addValue("refId", t.refId())
                        .addValue("milestoneRole",
                                t.milestoneRole() == null ? null : t.milestoneRole().name())
                        .addValue("sessionType",
                                t.sessionType() == null ? null : t.sessionType().name())
                        .addValue("durationMinutes", t.durationMinutes())
                        .addValue("postSessionSurveyId", t.postSessionSurveyId())
                        .addValue("dueDate", t.dueDate())
                        .addValue("status", t.status().name())
                        .addValue("aiDraft", t.aiDraft())
                        .addValue("position", i));
            }
        }
        batchUpdate("""
                INSERT INTO program_tasks (id, module_id, name, task_type, ref_id,
                                           milestone_role, session_type, duration_minutes,
                                           post_session_survey_id,
                                           due_date, status, ai_draft, position)
                VALUES (:id, :moduleId, :name, :taskType, :refId,
                        :milestoneRole, :sessionType, :durationMinutes,
                        :postSessionSurveyId,
                        :dueDate, :status, :aiDraft, :position)
                ON CONFLICT (id) DO UPDATE SET
                    module_id      = EXCLUDED.module_id,
                    name           = EXCLUDED.name,
                    task_type      = EXCLUDED.task_type,
                    ref_id         = EXCLUDED.ref_id,
                    milestone_role = EXCLUDED.milestone_role,
                    session_type           = EXCLUDED.session_type,
                    duration_minutes       = EXCLUDED.duration_minutes,
                    post_session_survey_id = EXCLUDED.post_session_survey_id,
                    due_date       = EXCLUDED.due_date,
                    status         = EXCLUDED.status,
                    ai_draft       = EXCLUDED.ai_draft,
                    position       = EXCLUDED.position,
                    updated_at     = now()
                """, batch);
    }

    private void upsertFields(List<ModuleUpsert> board) {
        List<SqlParameterSource> batch = new ArrayList<>();
        for (ModuleUpsert m : board) {
            for (TaskUpsert t : m.tasks()) {
                List<FieldUpsert> fields = t.fields();
                for (int i = 0; i < fields.size(); i++) {
                    FieldUpsert f = fields.get(i);
                    batch.add(new MapSqlParameterSource("id", f.id())
                            .addValue("taskId", t.id())
                            .addValue("fieldType", f.type().name())
                            .addValue("required", f.required())
                            .addValue("position", i)
                            .addValue("config", writeJson(f.config())));
                }
            }
        }
        batchUpdate("""
                INSERT INTO program_task_fields (id, task_id, field_type, required, position, config)
                VALUES (:id, :taskId, :fieldType, :required, :position, cast(:config AS jsonb))
                ON CONFLICT (id) DO UPDATE SET
                    task_id    = EXCLUDED.task_id,
                    field_type = EXCLUDED.field_type,
                    required   = EXCLUDED.required,
                    position   = EXCLUDED.position,
                    config     = EXCLUDED.config
                """, batch);
    }

    /**
     * Tasks the payload dropped — fields still cascade, but submissions are
     * RESTRICT since V212, so this force-confirmed destruction (the caller
     * already 409ed via {@link #memberWorkAtRisk} unless {@code force}) clears
     * them explicitly first.
     */
    private void deleteTasksNotIn(UUID cohortId, Collection<UUID> keptTaskIds) {
        SqlParameterSource params = new MapSqlParameterSource("cohortId", cohortId)
                .addValue("keptTaskIds", withSentinel(keptTaskIds));
        jdbc.update("""
                DELETE FROM program_submissions ps
                USING program_tasks t, program_modules m
                WHERE ps.task_id = t.id AND m.id = t.module_id AND m.cohort_id = :cohortId
                  AND t.id NOT IN (:keptTaskIds)
                """, params);
        jdbc.update("""
                DELETE FROM program_tasks t
                USING program_modules m
                WHERE m.id = t.module_id AND m.cohort_id = :cohortId
                  AND t.id NOT IN (:keptTaskIds)
                """, params);
    }

    /** Modules the payload dropped — empty by now, every kept task moved home first. */
    private void deleteModulesNotIn(UUID cohortId, Collection<UUID> keptModuleIds) {
        jdbc.update("""
                DELETE FROM program_modules
                WHERE cohort_id = :cohortId AND id NOT IN (:keptModuleIds)
                """,
                new MapSqlParameterSource("cohortId", cohortId)
                        .addValue("keptModuleIds", withSentinel(keptModuleIds)));
    }

    private void deleteFieldsNotIn(UUID cohortId, Collection<UUID> keptFieldIds) {
        jdbc.update("""
                DELETE FROM program_task_fields f
                USING program_tasks t, program_modules m
                WHERE t.id = f.task_id AND m.id = t.module_id AND m.cohort_id = :cohortId
                  AND f.id NOT IN (:keptFieldIds)
                """,
                new MapSqlParameterSource("cohortId", cohortId)
                        .addValue("keptFieldIds", withSentinel(keptFieldIds)));
    }

    /**
     * Audience is a set, so it is replaced wholesale. The insert selects from
     * {@code users} so a member erased since the board was read is simply
     * dropped instead of failing the whole save on the FK.
     */
    private void replaceAudiences(List<ModuleUpsert> board) {
        jdbc.update("DELETE FROM program_module_members WHERE module_id IN (:moduleIds)",
                new MapSqlParameterSource("moduleIds", withSentinel(moduleIds(board))));

        List<SqlParameterSource> batch = new ArrayList<>();
        for (ModuleUpsert m : board) {
            for (UUID userId : m.memberIds()) {
                batch.add(new MapSqlParameterSource("moduleId", m.id()).addValue("userId", userId));
            }
        }
        batchUpdate("""
                INSERT INTO program_module_members (module_id, user_id)
                SELECT :moduleId, u.id FROM users u WHERE u.id = :userId
                ON CONFLICT DO NOTHING
                """, batch);
    }

    /**
     * Pillar tags are a set too, so they are replaced wholesale like the
     * audience. Only KEPT tasks are cleared — a task the payload dropped took
     * its tags with it through the FK's cascade.
     */
    private void replacePillarTags(List<ModuleUpsert> board) {
        jdbc.update("DELETE FROM program_task_pillars WHERE task_id IN (:taskIds)",
                new MapSqlParameterSource("taskIds", withSentinel(taskIds(board))));

        List<SqlParameterSource> batch = new ArrayList<>();
        for (ModuleUpsert m : board) {
            for (TaskUpsert t : m.tasks()) {
                for (UUID pillarId : t.pillarIds()) {
                    batch.add(new MapSqlParameterSource("taskId", t.id())
                            .addValue("pillarId", pillarId));
                }
            }
        }
        batchUpdate("""
                INSERT INTO program_task_pillars (task_id, pillar_id)
                VALUES (:taskId, :pillarId)
                ON CONFLICT DO NOTHING
                """, batch);
    }

    /* ------------------------------------------------------------------ helpers */

    /** Every module id the board holds — the "keep" set a restore reconciles against. */
    public static List<UUID> moduleIds(List<ModuleUpsert> board) {
        return board.stream().map(ModuleUpsert::id).toList();
    }

    /** Every task id the board holds. */
    public static List<UUID> taskIds(List<ModuleUpsert> board) {
        return board.stream().flatMap(m -> m.tasks().stream()).map(TaskUpsert::id).toList();
    }

    /** Every field id the board holds (the service mints missing ones first). */
    public static List<UUID> fieldIds(List<ModuleUpsert> board) {
        return board.stream()
                .flatMap(m -> m.tasks().stream())
                .flatMap(t -> t.fields().stream())
                .map(FieldUpsert::id)
                .toList();
    }

    private void batchUpdate(String sql, List<SqlParameterSource> batch) {
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(sql, batch.toArray(SqlParameterSource[]::new));
        }
    }

    private static List<UUID> withSentinel(Collection<UUID> ids) {
        List<UUID> out = new ArrayList<>(ids);
        out.add(NONE);
        return out;
    }

    private String writeJson(Object value) {
        return json.writeValueAsString(value == null ? java.util.Map.of() : value);
    }
}
