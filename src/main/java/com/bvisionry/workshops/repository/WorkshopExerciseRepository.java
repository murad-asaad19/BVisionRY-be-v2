package com.bvisionry.workshops.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.workshops.domain.WorkshopExercise;

public interface WorkshopExerciseRepository extends JpaRepository<WorkshopExercise, UUID> {

    List<WorkshopExercise> findByWorkshopIdOrderByPositionAscCreatedAtAsc(UUID workshopId);

    /** Exercise count for a workshop — a COUNT query, not load-all-then-.size(). */
    int countByWorkshopId(UUID workshopId);

    @Query("SELECT coalesce(max(e.position), -1) + 1 FROM WorkshopExercise e WHERE e.workshopId = :workshopId")
    int nextPosition(@Param("workshopId") UUID workshopId);
}
