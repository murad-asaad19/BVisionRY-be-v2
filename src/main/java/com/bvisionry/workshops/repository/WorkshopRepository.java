package com.bvisionry.workshops.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.workshops.domain.Workshop;

public interface WorkshopRepository extends JpaRepository<Workshop, UUID> {

    List<Workshop> findByOrgIdOrderByPositionAscCreatedAtAsc(UUID orgId);

    boolean existsByOrgIdAndNameIgnoreCase(UUID orgId, String name);

    @Query("SELECT coalesce(max(w.position), -1) + 1 FROM Workshop w WHERE w.orgId = :orgId")
    int nextPosition(@Param("orgId") UUID orgId);

    /** The workshops the user is enrolled in (member of a team), in workshop order. */
    @Query(value = """
            SELECT w.id AS id, w.name AS name, w.status AS status,
                   t.name AS teamName, wtm.is_lead AS lead
            FROM workshops w
            JOIN workshop_team_members wtm ON wtm.workshop_id = w.id AND wtm.user_id = :userId
            JOIN workshop_teams t ON t.id = wtm.team_id
            WHERE w.org_id = :orgId
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
}
