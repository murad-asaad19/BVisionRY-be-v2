package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.entity.ExerciseSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExerciseSubmissionRepository extends JpaRepository<ExerciseSubmission, UUID> {

    List<ExerciseSubmission> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<ExerciseSubmission> findByAssignmentId(UUID assignmentId);

    List<ExerciseSubmission> findByAssignmentIdIn(List<UUID> assignmentIds);
}
