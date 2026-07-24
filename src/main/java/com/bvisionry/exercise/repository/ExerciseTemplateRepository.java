package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExerciseTemplateRepository extends JpaRepository<ExerciseTemplate, UUID> {

    List<ExerciseTemplate> findAllByOrderByCreatedAtDesc();

    List<ExerciseTemplate> findByStatusOrderByCreatedAtDesc(ExerciseTemplateStatus status);

    @Query("select t from ExerciseTemplate t left join fetch t.columns where t.id = :id")
    Optional<ExerciseTemplate> findByIdWithColumns(@Param("id") UUID id);
}
