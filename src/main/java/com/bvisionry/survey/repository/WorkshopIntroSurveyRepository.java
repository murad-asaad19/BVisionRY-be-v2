package com.bvisionry.survey.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bvisionry.survey.entity.SurveyResponse;

/**
 * Native reads into the {@code workshops} slice for the pre-workshop intro
 * survey flow. Mirrors the inverse trick the workshops slice already uses to
 * read {@code surveys}: a native projection keeps the survey slice free of a
 * survey->workshops Java dependency (the architecture-freeze rule forbids new
 * cross-feature coupling), while still authorizing the caller by enrollment.
 */
public interface WorkshopIntroSurveyRepository extends Repository<SurveyResponse, UUID> {

    /**
     * The intro-survey pairing + lifecycle for a workshop the user is enrolled
     * in (a member of one of its teams). Empty when the workshop doesn't exist
     * or the user isn't enrolled — the caller treats both as 404 so neither the
     * workshop's existence nor its membership leaks.
     */
    @Query(value = """
            SELECT w.pre_workshop_survey_id AS surveyId, w.status AS status
            FROM workshops w
            JOIN workshop_team_members wtm
              ON wtm.workshop_id = w.id AND wtm.user_id = :userId
            WHERE w.id = :workshopId
            """, nativeQuery = true)
    Optional<WorkshopIntroRow> findEnrolledIntro(@Param("workshopId") UUID workshopId,
                                                 @Param("userId") UUID userId);

    interface WorkshopIntroRow {
        UUID getSurveyId();
        String getStatus();
    }

    /** True once this member has submitted the workshop's intro survey (the gate key). */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM survey_responses
                WHERE survey_id = :surveyId
                  AND workshop_id = :workshopId
                  AND respondent_user_id = :userId)
            """, nativeQuery = true)
    boolean hasIntroResponse(@Param("surveyId") UUID surveyId,
                             @Param("workshopId") UUID workshopId,
                             @Param("userId") UUID userId);
}
