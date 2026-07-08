package com.bvisionry.workshops.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.workshops.domain.Workshop;

public interface WorkshopRepository extends JpaRepository<Workshop, UUID> {

    List<Workshop> findByOrgIdOrderByPositionAscCreatedAtAsc(UUID orgId);

    boolean existsByOrgIdAndNameIgnoreCase(UUID orgId, String name);

    @Query("SELECT coalesce(max(w.position), -1) + 1 FROM Workshop w WHERE w.orgId = :orgId")
    int nextPosition(@Param("orgId") UUID orgId);

    /**
     * The workshops the user is enrolled in (member of a team), in workshop
     * order. Drafts are admin-only — hidden until published.
     */
    @Query(value = """
            SELECT w.id AS id, w.name AS name, w.status AS status,
                   t.name AS teamName, wtm.is_lead AS lead
            FROM workshops w
            JOIN workshop_team_members wtm ON wtm.workshop_id = w.id AND wtm.user_id = :userId
            JOIN workshop_teams t ON t.id = wtm.team_id
            WHERE w.org_id = :orgId AND w.status <> 'DRAFT'
            ORDER BY w.position, w.created_at
            """, nativeQuery = true)
    List<MyWorkshopRow> findMyWorkshops(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    interface MyWorkshopRow {
        UUID getId();
        String getName();
        String getStatus();
        String getTeamName();
        boolean getLead();
    }

    /**
     * The published survey paired to a workshop's thank-you screen. Native
     * lookup keeps the slice free of a workshops→survey dependency (the same
     * trick programflow uses for user rows).
     * ponytail: swap for a common-port abstraction if a second slice needs it.
     */
    @Query(value = """
            SELECT s.id AS id, s.name AS name, s.public_token AS publicToken
            FROM surveys s
            WHERE s.id = :surveyId AND s.status = 'PUBLISHED'
            """, nativeQuery = true)
    Optional<PublishedSurveyRow> findPublishedSurvey(@Param("surveyId") UUID surveyId);

    interface PublishedSurveyRow {
        UUID getId();
        String getName();
        UUID getPublicToken();
    }

    /**
     * Whether this member has completed the workshop's pre-workshop intro survey
     * — the play gate. Native read into {@code survey_responses} keeps the slice
     * free of a workshops->survey Java dependency (the same trick as
     * {@link #findPublishedSurvey}).
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM survey_responses
                WHERE survey_id = :surveyId
                  AND workshop_id = :workshopId
                  AND respondent_user_id = :userId)
            """, nativeQuery = true)
    boolean hasIntroSurveyResponse(@Param("surveyId") UUID surveyId,
                                   @Param("workshopId") UUID workshopId,
                                   @Param("userId") UUID userId);

    /** Drop every intro-survey response of a workshop (admin "reset results"). */
    @Modifying
    @Query(value = "DELETE FROM survey_responses WHERE workshop_id = :workshopId", nativeQuery = true)
    void deleteIntroResponsesByWorkshopId(@Param("workshopId") UUID workshopId);

    /** Drop one member's intro-survey response (admin "reset member answers"). */
    @Modifying
    @Query(value = """
            DELETE FROM survey_responses
            WHERE workshop_id = :workshopId AND respondent_user_id = :userId
            """, nativeQuery = true)
    void deleteIntroResponseByWorkshopIdAndUserId(@Param("workshopId") UUID workshopId,
                                                  @Param("userId") UUID userId);
}
