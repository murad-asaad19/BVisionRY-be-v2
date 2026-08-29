package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.entity.PublicExerciseResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PublicExerciseResponseRepository
        extends JpaRepository<PublicExerciseResponse, UUID> {

    Page<PublicExerciseResponse> findByTemplateIdOrderBySubmittedAtDesc(UUID templateId,
                                                                        Pageable pageable);

    Optional<PublicExerciseResponse> findByIdAndTemplateId(UUID id, UUID templateId);

    long countByTemplateId(UUID templateId);
}
