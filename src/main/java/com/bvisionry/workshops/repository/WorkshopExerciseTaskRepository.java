package com.bvisionry.workshops.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.workshops.domain.WorkshopExerciseTask;

public interface WorkshopExerciseTaskRepository extends JpaRepository<WorkshopExerciseTask, UUID> {

    List<WorkshopExerciseTask> findByExerciseIdOrderByPositionAscCreatedAtAsc(UUID exerciseId);

    List<WorkshopExerciseTask> findByExerciseIdInOrderByPositionAscCreatedAtAsc(List<UUID> exerciseIds);

    @Query("SELECT coalesce(max(t.position), -1) + 1 FROM WorkshopExerciseTask t WHERE t.exerciseId = :exerciseId")
    int nextPosition(@Param("exerciseId") UUID exerciseId);

    void deleteByExerciseId(UUID exerciseId);
}
