package com.bvisionry.programflow.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskStatus;

public interface ProgramTaskRepository extends JpaRepository<ProgramTask, UUID> {

    @Query("select t from ProgramTask t join fetch t.module where t.id = :id")
    Optional<ProgramTask> findWithModule(@Param("id") UUID id);

    /**
     * Live tasks that may need a due-soon reminder: due inside [from, to] and
     * never reminded. The caller narrows to each cohort's own due-soon window.
     * LESSON only for now — the reminder's "already submitted" filter reads
     * program_submissions, which other types don't have; Phase E (coach
     * workspace) teaches it per-type done state and lifts this.
     */
    @Query("""
            select t from ProgramTask t join fetch t.module
            where t.status = :status and t.dueReminderSentAt is null
              and t.taskType = com.bvisionry.programflow.domain.ProgramTaskType.LESSON
              and t.dueDate between :from and :to
            """)
    List<ProgramTask> findDueForReminder(
            @Param("status") ProgramTaskStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Every task of the cohort holding the given milestone role, any publish
     * status — drafts count too, so two admins can't stage two baselines.
     */
    @Query("""
            select t from ProgramTask t join fetch t.module m
            where m.cohortId = :cohortId and t.milestoneRole = :role
            """)
    List<ProgramTask> findByCohortAndMilestoneRole(
            @Param("cohortId") UUID cohortId,
            @Param("role") com.bvisionry.programflow.domain.MilestoneRole role);
}
