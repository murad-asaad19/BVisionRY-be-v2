package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.CohortGrowthSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query carries the tenant ({@code orgId}) — a platform cohort spans orgs
 * (spec §13), so a cohort-only read would hand one org another's report.
 */
public interface CohortGrowthSummaryRepository extends JpaRepository<CohortGrowthSummary, UUID> {

    /** The poll target: newest row whatever its status, so GENERATING and FAILED both surface. */
    Optional<CohortGrowthSummary> findTopByOrgIdAndCohortIdOrderByGeneratedAtDesc(UUID orgId, UUID cohortId);

    List<CohortGrowthSummary> findByOrgIdAndCohortIdOrderByGeneratedAtDesc(UUID orgId, UUID cohortId);

    Optional<CohortGrowthSummary> findByIdAndOrgId(UUID id, UUID orgId);
}
