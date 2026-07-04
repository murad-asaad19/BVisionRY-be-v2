package com.bvisionry.programflow.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.Team;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    boolean existsByOrgIdAndNameIgnoreCase(UUID orgId, String name);

    /** Active members (role MEMBER) of the org with their current team, if any. */
    @Query(value = """
            SELECT u.id AS id, u.name AS name, u.email AS email, tm.team_id AS teamId
            FROM users u
            LEFT JOIN team_members tm ON tm.user_id = u.id
            WHERE u.organization_id = :orgId
              AND u.status = 'ACTIVE'
              AND u.role = 'MEMBER'
            ORDER BY u.name
            """, nativeQuery = true)
    List<OrgMemberRow> findOrgMembers(@Param("orgId") UUID orgId);

    @Query(value = "SELECT count(*) FROM team_members WHERE team_id = :teamId", nativeQuery = true)
    long countMembers(@Param("teamId") UUID teamId);

    /** Removes the user from whichever team they are on (no-op when unassigned). */
    @Modifying
    @Query(value = "DELETE FROM team_members WHERE user_id = :userId", nativeQuery = true)
    void removeMembership(@Param("userId") UUID userId);

    /** Caller must {@link #removeMembership(UUID)} first — PK is on user_id. */
    @Modifying
    @Query(value = "INSERT INTO team_members (user_id, team_id) VALUES (:userId, :teamId)", nativeQuery = true)
    void addMembership(@Param("userId") UUID userId, @Param("teamId") UUID teamId);
}
