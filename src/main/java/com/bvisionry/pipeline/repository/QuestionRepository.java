package com.bvisionry.pipeline.repository;

import com.bvisionry.pipeline.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByPillarIdOrderByDisplayOrder(UUID pillarId);
    Optional<Question> findByIdAndPillarId(UUID id, UUID pillarId);

    @Query("SELECT COALESCE(MAX(q.displayOrder), -1) FROM Question q WHERE q.pillar.id = :pillarId")
    int findMaxDisplayOrderByPillarId(@Param("pillarId") UUID pillarId);

    long countByPillarId(UUID pillarId);
}
