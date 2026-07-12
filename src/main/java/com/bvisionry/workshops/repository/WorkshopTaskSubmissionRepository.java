package com.bvisionry.workshops.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.workshops.domain.WorkshopTaskSubmission;

public interface WorkshopTaskSubmissionRepository extends JpaRepository<WorkshopTaskSubmission, UUID> {

    List<WorkshopTaskSubmission> findByTeamIdAndTaskIdIn(UUID teamId, Collection<UUID> taskIds);

    @Modifying
    @Query(value = """
            DELETE FROM workshop_task_submissions
            WHERE task_id IN (SELECT id FROM workshop_exercise_tasks WHERE exercise_id = :exerciseId)
            """, nativeQuery = true)
    void deleteByExerciseId(@Param("exerciseId") UUID exerciseId);

    @Modifying
    @Query(value = """
            DELETE FROM workshop_task_submissions
            WHERE task_id IN (
                SELECT t.id FROM workshop_exercise_tasks t
                JOIN workshop_exercises e ON e.id = t.exercise_id
                WHERE e.workshop_id = :workshopId)
            """, nativeQuery = true)
    void deleteByWorkshopId(@Param("workshopId") UUID workshopId);

    /** Admin "reset member answers": drop one user's submissions across the workshop. */
    @Modifying
    @Query(value = """
            DELETE FROM workshop_task_submissions
            WHERE user_id = :userId AND task_id IN (
                SELECT t.id FROM workshop_exercise_tasks t
                JOIN workshop_exercises e ON e.id = t.exercise_id
                WHERE e.workshop_id = :workshopId)
            """, nativeQuery = true)
    void deleteByWorkshopIdAndUserId(@Param("workshopId") UUID workshopId, @Param("userId") UUID userId);

    /** Every completed (task, team, user) triple of a workshop — the live-board position source. */
    @Query(value = """
            SELECT s.task_id AS taskId, s.team_id AS teamId, s.user_id AS userId
            FROM workshop_task_submissions s
            JOIN workshop_exercise_tasks t ON t.id = s.task_id
            JOIN workshop_exercises e ON e.id = t.exercise_id
            WHERE e.workshop_id = :workshopId
              AND s.completed_at IS NOT NULL
            """, nativeQuery = true)
    List<CompletionRow> findCompletions(@Param("workshopId") UUID workshopId);

    interface CompletionRow {
        UUID getTaskId();
        UUID getTeamId();
        UUID getUserId();
    }

    /**
     * The admin analytics log: every completed task of a workshop, newest
     * first, with performer name, team, role, attempts and duration.
     */
    @Query(value = """
            SELECT s.id AS id, e.title AS exerciseTitle, t.title AS taskTitle,
                   t.task_type AS taskType, t.assignee AS assignee,
                   u.id AS userId, u.name AS userName, wt.name AS teamName,
                   s.attempts AS attempts, s.elapsed_ms AS elapsedMs,
                   s.completed_at AS completedAt
            FROM workshop_task_submissions s
            JOIN workshop_exercise_tasks t ON t.id = s.task_id
            JOIN workshop_exercises e ON e.id = t.exercise_id
            JOIN workshop_teams wt ON wt.id = s.team_id
            JOIN users u ON u.id = s.user_id
            WHERE e.workshop_id = :workshopId
              AND s.completed_at IS NOT NULL
            ORDER BY s.completed_at DESC
            """, nativeQuery = true)
    List<AnalyticsRow> findAnalytics(@Param("workshopId") UUID workshopId);

    interface AnalyticsRow {
        UUID getId();
        String getExerciseTitle();
        String getTaskTitle();
        String getTaskType();
        String getAssignee();
        UUID getUserId();
        String getUserName();
        String getTeamName();
        Integer getAttempts();
        Long getElapsedMs();
        /** timestamptz comes back as Instant from a native projection. */
        Instant getCompletedAt();
    }
}
