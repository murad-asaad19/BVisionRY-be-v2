package com.bvisionry.coaching.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.coaching.domain.CoachAssignment;

/**
 * Coach-assignment rows. Every finder carries the org predicate — there is
 * deliberately no bare-ID lookup on this repository, so no {@code require*}
 * guard is ever needed.
 */
public interface CoachAssignmentRepository extends JpaRepository<CoachAssignment, UUID> {

    List<CoachAssignment> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    Optional<CoachAssignment> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByOrgIdAndCoachIdAndCohortId(UUID orgId, UUID coachId, UUID cohortId);

    boolean existsByOrgIdAndCoachIdAndMemberId(UUID orgId, UUID coachId, UUID memberId);

    /** The V176 org-wide grain: one row per (coach, org) with neither target set. */
    boolean existsByOrgIdAndCoachIdAndCohortIdIsNullAndMemberIdIsNull(UUID orgId, UUID coachId);
}
