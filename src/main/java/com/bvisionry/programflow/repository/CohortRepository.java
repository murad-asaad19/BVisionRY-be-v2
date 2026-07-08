package com.bvisionry.programflow.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.Cohort;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    /** All cohorts of an org, in board order (admin cohort switcher). */
    List<Cohort> findByOrgIdOrderByPositionAsc(UUID orgId);

    /** The cohorts a learner is enrolled in — ACTIVE first, then by position. */
    @Query("""
            SELECT c FROM Cohort c
            JOIN c.memberIds m
            WHERE m = :userId
            ORDER BY c.status ASC, c.position ASC
            """)
    List<Cohort> findEnrolled(@Param("userId") UUID userId);

    /**
     * Every organization with its active-learner and cohort counts, for the
     * Program Flow org switcher (cohortCount &gt; 0) and the "add organization"
     * picker (cohortCount = 0). Soft-coupled by SQL like TeamRepository.
     */
    @Query(value = """
            SELECT o.id AS id, o.name AS name, o.description AS description,
                   (SELECT count(*) FROM users u WHERE u.organization_id = o.id
                     AND u.role = 'MEMBER' AND u.status = 'ACTIVE') AS memberCount,
                   (SELECT count(*) FROM cohorts c WHERE c.org_id = o.id) AS cohortCount
            FROM organizations o
            ORDER BY o.name
            """, nativeQuery = true)
    List<OrgProgramRow> findOrgProgramRows();
}
