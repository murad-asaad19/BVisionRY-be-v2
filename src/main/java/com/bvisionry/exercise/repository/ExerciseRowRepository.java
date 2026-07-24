package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.entity.ExerciseRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExerciseRowRepository extends JpaRepository<ExerciseRow, UUID> {

    List<ExerciseRow> findBySubmissionIdAndDeletedAtIsNullOrderByDisplayOrder(UUID submissionId);

    List<ExerciseRow> findBySubmissionId(UUID submissionId);
}
