package com.bvisionry.survey.repository;

import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SurveyRepository extends JpaRepository<Survey, UUID> {

    List<Survey> findAllByOrderByUpdatedAtDesc();

    List<Survey> findByStatusOrderByUpdatedAtDesc(SurveyStatus status);

    Optional<Survey> findByPublicToken(UUID publicToken);

    @Query("SELECT DISTINCT s FROM Survey s LEFT JOIN FETCH s.pillars WHERE s.id = :id")
    Optional<Survey> findByIdWithPillars(@Param("id") UUID id);

    List<Survey> findByStatus(SurveyStatus status);
}
