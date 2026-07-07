package com.bvisionry.workshops.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.workshops.domain.WorkshopTeam;

public interface WorkshopTeamRepository extends JpaRepository<WorkshopTeam, UUID> {

    List<WorkshopTeam> findByWorkshopIdOrderByPositionAscCreatedAtAsc(UUID workshopId);

    boolean existsByWorkshopIdAndNameIgnoreCase(UUID workshopId, String name);

    @Query("SELECT coalesce(max(t.position), -1) + 1 FROM WorkshopTeam t WHERE t.workshopId = :workshopId")
    int nextPosition(@Param("workshopId") UUID workshopId);

    /** Active MEMBER-role users of the org with their team in this workshop, if any. */
    @Query(value = """
            SELECT u.id AS id, u.name AS name, u.email AS email,
                   wtm.team_id AS teamId, coalesce(wtm.is_lead, false) AS lead
            FROM users u
            LEFT JOIN workshop_team_members wtm
                   ON wtm.user_id = u.id AND wtm.workshop_id = :workshopId
            WHERE u.organization_id = :orgId
              AND u.status = 'ACTIVE'
              AND u.role = 'MEMBER'
            ORDER BY u.name
            """, nativeQuery = true)
    List<WorkshopMemberRow> findOrgMembers(@Param("orgId") UUID orgId,
                                           @Param("workshopId") UUID workshopId);

    interface WorkshopMemberRow {
        UUID getId();
        String getName();
        String getEmail();
        UUID getTeamId();
        boolean getLead();
    }

    @Query(value = """
            SELECT team_id AS teamId, is_lead AS lead
            FROM workshop_team_members
            WHERE workshop_id = :workshopId AND user_id = :userId
            """, nativeQuery = true)
    Optional<MembershipRow> findMembership(@Param("workshopId") UUID workshopId,
                                           @Param("userId") UUID userId);

    interface MembershipRow {
        UUID getTeamId();
        boolean getLead();
    }

    /** Team ids of a workshop with their member counts, least-filled first (ties by team position). */
    @Query(value = """
            SELECT t.id AS teamId, count(wtm.user_id) AS members
            FROM workshop_teams t
            LEFT JOIN workshop_team_members wtm ON wtm.team_id = t.id
            WHERE t.workshop_id = :workshopId
            GROUP BY t.id, t.position, t.created_at
            ORDER BY count(wtm.user_id) ASC, t.position ASC, t.created_at ASC
            """, nativeQuery = true)
    List<TeamFillRow> findTeamFill(@Param("workshopId") UUID workshopId);

    interface TeamFillRow {
        UUID getTeamId();
        long getMembers();
    }

    /** Removes the user from whichever team they are on in this workshop (no-op when unassigned). */
    @Modifying
    @Query(value = """
            DELETE FROM workshop_team_members
            WHERE workshop_id = :workshopId AND user_id = :userId
            """, nativeQuery = true)
    void removeMembership(@Param("workshopId") UUID workshopId, @Param("userId") UUID userId);

    /** Caller must {@link #removeMembership} first — PK is (workshop_id, user_id). */
    @Modifying
    @Query(value = """
            INSERT INTO workshop_team_members (workshop_id, user_id, team_id, is_lead)
            VALUES (:workshopId, :userId, :teamId, false)
            """, nativeQuery = true)
    void addMembership(@Param("workshopId") UUID workshopId,
                       @Param("userId") UUID userId,
                       @Param("teamId") UUID teamId);

    @Modifying
    @Query(value = "UPDATE workshop_team_members SET is_lead = false WHERE team_id = :teamId", nativeQuery = true)
    void clearLead(@Param("teamId") UUID teamId);

    /** Caller must {@link #clearLead} first — a partial unique index enforces one lead per team. */
    @Modifying
    @Query(value = """
            UPDATE workshop_team_members SET is_lead = true
            WHERE team_id = :teamId AND user_id = :userId
            """, nativeQuery = true)
    int setLead(@Param("teamId") UUID teamId, @Param("userId") UUID userId);

    @Query(value = "SELECT count(*) FROM workshop_team_members WHERE workshop_id = :workshopId", nativeQuery = true)
    long countWorkshopMembers(@Param("workshopId") UUID workshopId);

    /** Non-lead member ids of a team (the QUESTION-task audience). */
    @Query(value = """
            SELECT user_id FROM workshop_team_members
            WHERE team_id = :teamId AND is_lead = false
            """, nativeQuery = true)
    List<UUID> findNonLeadMemberIds(@Param("teamId") UUID teamId);
}
