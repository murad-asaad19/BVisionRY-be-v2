package com.bvisionry.workshops.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.workshops.domain.WorkshopExerciseRun;

public interface WorkshopExerciseRunRepository extends JpaRepository<WorkshopExerciseRun, UUID> {

    List<WorkshopExerciseRun> findByTeamId(UUID teamId);

    @Query("""
            SELECT r FROM WorkshopExerciseRun r
            WHERE r.exerciseId IN (SELECT e.id FROM WorkshopExercise e WHERE e.workshopId = :workshopId)
            """)
    List<WorkshopExerciseRun> findByWorkshopId(@Param("workshopId") UUID workshopId);

    /** Bulk delete (one native DELETE), like {@link #deleteByWorkshopId} — not a derived per-row delete. */
    @Modifying
    @Query(value = "DELETE FROM workshop_exercise_runs WHERE exercise_id = :exerciseId", nativeQuery = true)
    void deleteByExerciseId(@Param("exerciseId") UUID exerciseId);

    /** Reset every run (and cascade-owned state) of a whole workshop. */
    @Modifying
    @Query(value = """
            DELETE FROM workshop_exercise_runs
            WHERE exercise_id IN (SELECT id FROM workshop_exercises WHERE workshop_id = :workshopId)
            """, nativeQuery = true)
    void deleteByWorkshopId(@Param("workshopId") UUID workshopId);
}
