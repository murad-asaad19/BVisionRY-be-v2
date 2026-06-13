package com.bvisionry.pipeline.repository;

import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.pipeline.entity.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    List<Pipeline> findByStatusOrderByUpdatedAtDesc(PipelineStatus status);
    List<Pipeline> findAllByOrderByUpdatedAtDesc();

    @Query("SELECT p FROM Pipeline p LEFT JOIN FETCH p.pillars WHERE p.id = :id")
    Optional<Pipeline> findByIdWithPillars(@Param("id") UUID id);

    @Query("SELECT DISTINCT p FROM Pipeline p LEFT JOIN FETCH p.pillars WHERE p.id = :id")
    Optional<Pipeline> findByIdWithPillarsAndQuestions(@Param("id") UUID id);

    @Query("SELECT MAX(p.version) FROM Pipeline p WHERE p.name = :name")
    Optional<Integer> findMaxVersionByName(@Param("name") String name);

    List<Pipeline> findByStatus(PipelineStatus status);
}
