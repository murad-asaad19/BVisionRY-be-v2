package com.bvisionry.programflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.programflow.domain.CohortOrgAssignment;

public interface CohortOrgAssignmentRepository
        extends JpaRepository<CohortOrgAssignment, CohortOrgAssignment.Key> {

    List<CohortOrgAssignment> findByCohortId(UUID cohortId);

    List<CohortOrgAssignment> findByOrgId(UUID orgId);

    Optional<CohortOrgAssignment> findByCohortIdAndOrgId(UUID cohortId, UUID orgId);

    boolean existsByCohortIdAndOrgId(UUID cohortId, UUID orgId);
}
