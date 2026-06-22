package com.bvisionry.survey.repository;

import com.bvisionry.survey.entity.SurveyAnswer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, UUID> {

    /**
     * Eager-fetch the question + pillar graph in one round-trip — the result is
     * sorted by pillar/question display order in the service layer, which would
     * otherwise trigger N+1 lazy loads per answer.
     */
    @EntityGraph(attributePaths = {"question", "question.pillar"})
    @Query("select a from SurveyAnswer a where a.response.id = :responseId")
    List<SurveyAnswer> findByResponseId(UUID responseId);

    @EntityGraph(attributePaths = {"question", "question.pillar"})
    @Query("select a from SurveyAnswer a where a.response.survey.id = :surveyId")
    List<SurveyAnswer> findByResponseSurveyId(UUID surveyId);

    /**
     * Live-page variant: load only answers whose question is opted into live
     * analytics. Pushing the {@code liveAnalyticsEnabled} filter into the query
     * keeps the polled live endpoint's cost proportional to the live questions,
     * not the survey's total response volume.
     */
    @EntityGraph(attributePaths = {"question", "question.pillar"})
    @Query("select a from SurveyAnswer a where a.response.survey.id = :surveyId "
            + "and a.question.liveAnalyticsEnabled = true")
    List<SurveyAnswer> findLiveByResponseSurveyId(UUID surveyId);
}
