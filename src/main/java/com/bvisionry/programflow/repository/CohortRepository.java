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
}
