package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.entity.ExerciseColumn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ExerciseColumnRepository extends JpaRepository<ExerciseColumn, UUID> {

    List<ExerciseColumn> findByTemplateIdOrderByDisplayOrder(UUID templateId);

    int countByTemplateId(UUID templateId);

    /** Column counts for every template in one round-trip (template list view). */
    @Query("select c.template.id, count(c) from ExerciseColumn c group by c.template.id")
    List<Object[]> countAllGroupByTemplate();
}
