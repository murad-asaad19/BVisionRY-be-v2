package com.bvisionry.survey.repository;

import com.bvisionry.survey.entity.SurveyPillar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SurveyPillarRepository extends JpaRepository<SurveyPillar, UUID> {

    List<SurveyPillar> findBySurveyIdOrderByDisplayOrder(UUID surveyId);

    Optional<SurveyPillar> findByIdAndSurveyId(UUID id, UUID surveyId);

    @Query("SELECT p FROM SurveyPillar p LEFT JOIN FETCH p.questions WHERE p.id = :id AND p.survey.id = :surveyId")
    Optional<SurveyPillar> findByIdAndSurveyIdWithQuestions(@Param("id") UUID id, @Param("surveyId") UUID surveyId);

    @Query("SELECT COALESCE(MAX(p.displayOrder), -1) FROM SurveyPillar p WHERE p.survey.id = :surveyId")
    int findMaxDisplayOrderBySurveyId(@Param("surveyId") UUID surveyId);
}
