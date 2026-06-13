package com.bvisionry.survey.repository;

import com.bvisionry.survey.entity.SurveyQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SurveyQuestionRepository extends JpaRepository<SurveyQuestion, UUID> {

    List<SurveyQuestion> findByPillarIdOrderByDisplayOrder(UUID pillarId);

    Optional<SurveyQuestion> findByIdAndPillarId(UUID id, UUID pillarId);

    @Query("SELECT COALESCE(MAX(q.displayOrder), -1) FROM SurveyQuestion q WHERE q.pillar.id = :pillarId")
    int findMaxDisplayOrderByPillarId(@Param("pillarId") UUID pillarId);
}
