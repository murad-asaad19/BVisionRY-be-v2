package com.bvisionry.pipeline.repository;

import com.bvisionry.pipeline.entity.Pillar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PillarRepository extends JpaRepository<Pillar, UUID> {
    List<Pillar> findByPipelineIdOrderByDisplayOrder(UUID pipelineId);

    @Query("SELECT p FROM Pillar p LEFT JOIN FETCH p.questions WHERE p.id = :id AND p.pipeline.id = :pipelineId")
    Optional<Pillar> findByIdAndPipelineIdWithQuestions(@Param("id") UUID id, @Param("pipelineId") UUID pipelineId);

    Optional<Pillar> findByIdAndPipelineId(UUID id, UUID pipelineId);

    @Query("SELECT p FROM Pillar p LEFT JOIN FETCH p.questions WHERE p.id = :id")
    Optional<Pillar> findByIdWithQuestions(@Param("id") UUID id);

    @Query("SELECT COALESCE(MAX(p.displayOrder), -1) FROM Pillar p WHERE p.pipeline.id = :pipelineId")
    int findMaxDisplayOrderByPipelineId(@Param("pipelineId") UUID pipelineId);

    long countByPipelineId(UUID pipelineId);
}
