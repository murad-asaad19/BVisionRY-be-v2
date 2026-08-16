package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.FounderComparison;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All queries carry either the tenant ({@code orgId}) or the identity scope
 * ({@code userId}) explicitly — no bare-ID loads (ArchUnit rule 5).
 */
public interface FounderComparisonRepository extends JpaRepository<FounderComparison, UUID> {

    Optional<FounderComparison> findByCohortIdAndUserId(UUID cohortId, UUID userId);

    Optional<FounderComparison> findByOrgIdAndCohortIdAndUserId(UUID orgId, UUID cohortId, UUID userId);

    List<FounderComparison> findByOrgIdAndCohortId(UUID orgId, UUID cohortId);

    void deleteByCohortIdAndUserId(UUID cohortId, UUID userId);
}
