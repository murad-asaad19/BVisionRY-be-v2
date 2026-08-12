package com.bvisionry.programflow.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.bvisionry.programflow.domain.BoardSnapshot;
import com.bvisionry.programflow.domain.BoardSnapshot.FieldSnap;
import com.bvisionry.programflow.domain.BoardSnapshot.ModuleSnap;
import com.bvisionry.programflow.domain.BoardSnapshot.TaskSnap;
import tools.jackson.databind.ObjectMapper;

/**
 * Restores a cohort's curriculum to a {@link BoardSnapshot}, in raw SQL.
 *
 * <p><strong>Why not JPA.</strong> A revert has to RE-CREATE rows deleted since
 * the checkpoint <em>under their original ids</em> — that is what re-attaches
 * the member work which points at a task by bare uuid with no FK
 * ({@code submissions.program_task_id}, {@code exercise_assignments.program_task_id},
 * {@code survey_responses.program_task_id}). Every program-flow entity declares
 * its id {@code insertable = false} with a {@code gen_random_uuid()} default, so
 * JPA structurally cannot insert one with a chosen id. Rather than a hybrid that
 * fights orphan-removal and flush ordering, the whole write is one deterministic
 * SQL sequence — the same stance as {@link TaskSpineRepository}.
 *
 * <p><strong>Order is the correctness.</strong> Modules and tasks are upserted
 * BEFORE anything is deleted, so a task that was dragged into a module created
 * after the checkpoint is moved home first and never gets caught by that
 * module's {@code ON DELETE CASCADE} — which would have taken its
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

    /** A task a revert would delete, and how many members have work against it. */
    public record DoomedTask(String taskName, long memberCount) {}

    /**
     * The tasks this revert would DELETE (cohort tasks the snapshot does not
     * hold) that carry member work, with the number of distinct members behind
     * it. Every place work can hang off a task is counted: the LESSON
     * submission table plus the three bare-uuid tags — assessment
     * ({@code submissions}, V164), exercise assignment and survey response
     * (both V173).
     */
    public List<DoomedTask> memberWorkAtRisk(UUID cohortId, Collection<UUID> keptTaskIds) {
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
                           WHERE sr.program_task_id = t.id) x
                ) w
                WHERE m.cohort_id = :cohortId
                  AND t.id NOT IN (:keptTaskIds)
                  AND w.member_count > 0
                ORDER BY t.name
                """,
                new MapSqlParameterSource("cohortId", cohortId)
                        .addValue("keptTaskIds", withSentinel(keptTaskIds)),
                (rs, i) -> new DoomedTask(rs.getString("task_name"), rs.getLong("member_count")));
    }

    /* -------------------------------------------------------------- the restore */

    /**
     * Makes the cohort's curriculum match {@code snapshot}: surviving rows are
     * updated, rows deleted since are re-created under their original ids, rows
     * created since are deleted. Positions are re-derived from list order, so
     * the restored board has no gaps.
     */
    public void restore(UUID cohortId, BoardSnapshot snapshot) {
        upsertModules(cohortId, snapshot);
        upsertTasks(snapshot);
        upsertFields(snapshot);
        // Only now is it safe to delete: every snapshot row sits under its
        // snapshot parent, so no cascade can reach one.
        deleteTasksNotIn(cohortId, snapshot.taskIds());
        deleteModulesNotIn(cohortId, snapshot.moduleIds());
        deleteFieldsNotIn(cohortId, snapshot.fieldIds());
        replaceAudiences(snapshot);
    }

    private void upsertModules(UUID cohortId, BoardSnapshot snapshot) {
        List<SqlParameterSource> batch = new ArrayList<>();
        List<ModuleSnap> mods = snapshot.modules();
        for (int i = 0; i < mods.size(); i++) {
            ModuleSnap m = mods.get(i);
            batch.add(new MapSqlParameterSource("id", m.id())
                    .addValue("cohortId", cohortId)
                    .addValue("name", m.name())
                    .addValue("summary", m.summary())
                    .addValue("pillarLabel", m.pillarLabel())
                    .addValue("position", i)
                    .addValue("lockMode", m.lockMode().name())
                    .addValue("unlockAt", m.unlockAt())
                    .addValue("assignMode", m.assignMode().name()));
        }
        batchUpdate("""
                INSERT INTO program_modules (id, cohort_id, name, summary, pillar_label,
                                             position, lock_mode, unlock_at, assign_mode)
                VALUES (:id, :cohortId, :name, :summary, :pillarLabel,
                        :position, :lockMode, :unlockAt, :assignMode)
                ON CONFLICT (id) DO UPDATE SET
                    name         = EXCLUDED.name,
                    summary      = EXCLUDED.summary,
                    pillar_label = EXCLUDED.pillar_label,
                    position     = EXCLUDED.position,
                    lock_mode    = EXCLUDED.lock_mode,
                    unlock_at    = EXCLUDED.unlock_at,
                    assign_mode  = EXCLUDED.assign_mode,
                    updated_at   = now()
                """, batch);
    }

    private void upsertTasks(BoardSnapshot snapshot) {
        List<SqlParameterSource> batch = new ArrayList<>();
        for (ModuleSnap m : snapshot.modules()) {
            List<TaskSnap> tasks = m.tasks();
            for (int i = 0; i < tasks.size(); i++) {
                TaskSnap t = tasks.get(i);
                batch.add(new MapSqlParameterSource("id", t.id())
                        .addValue("moduleId", m.id())
                        .addValue("name", t.name())
                        .addValue("taskType", t.taskType().name())
                        .addValue("refId", t.refId())
                        .addValue("milestoneRole",
                                t.milestoneRole() == null ? null : t.milestoneRole().name())
                        .addValue("dueDate", t.dueDate())
                        .addValue("status", t.status().name())
                        .addValue("aiDraft", t.aiDraft())
                        .addValue("position", i));
            }
        }
        batchUpdate("""
                INSERT INTO program_tasks (id, module_id, name, task_type, ref_id,
                                           milestone_role, due_date, status, ai_draft, position)
                VALUES (:id, :moduleId, :name, :taskType, :refId,
                        :milestoneRole, :dueDate, :status, :aiDraft, :position)
                ON CONFLICT (id) DO UPDATE SET
                    module_id      = EXCLUDED.module_id,
                    name           = EXCLUDED.name,
                    task_type      = EXCLUDED.task_type,
                    ref_id         = EXCLUDED.ref_id,
                    milestone_role = EXCLUDED.milestone_role,
                    due_date       = EXCLUDED.due_date,
                    status         = EXCLUDED.status,
                    ai_draft       = EXCLUDED.ai_draft,
                    position       = EXCLUDED.position,
                    updated_at     = now()
                """, batch);
    }

    private void upsertFields(BoardSnapshot snapshot) {
        List<SqlParameterSource> batch = new ArrayList<>();
        for (ModuleSnap m : snapshot.modules()) {
            for (TaskSnap t : m.tasks()) {
                List<FieldSnap> fields = t.fields();
                for (int i = 0; i < fields.size(); i++) {
                    FieldSnap f = fields.get(i);
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

    /** Tasks added since the checkpoint — cascades their fields and submissions. */
    private void deleteTasksNotIn(UUID cohortId, Collection<UUID> keptTaskIds) {
        jdbc.update("""
                DELETE FROM program_tasks t
                USING program_modules m
                WHERE m.id = t.module_id AND m.cohort_id = :cohortId
                  AND t.id NOT IN (:keptTaskIds)
                """,
                new MapSqlParameterSource("cohortId", cohortId)
                        .addValue("keptTaskIds", withSentinel(keptTaskIds)));
    }

    /** Modules added since — empty by now, every snapshot task was moved home first. */
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
     * {@code users} so a member deleted since the checkpoint is simply dropped
     * instead of failing the whole revert on the FK.
     */
    private void replaceAudiences(BoardSnapshot snapshot) {
        List<UUID> moduleIds = snapshot.moduleIds();
        jdbc.update("DELETE FROM program_module_members WHERE module_id IN (:moduleIds)",
                new MapSqlParameterSource("moduleIds", withSentinel(moduleIds)));

        List<SqlParameterSource> batch = new ArrayList<>();
        for (ModuleSnap m : snapshot.modules()) {
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

    /* ------------------------------------------------------------------ helpers */

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
