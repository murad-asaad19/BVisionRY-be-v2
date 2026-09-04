package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.FounderComparisonPillar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FounderComparisonPillarRepository
        extends JpaRepository<FounderComparisonPillar, UUID> {

    List<FounderComparisonPillar> findByComparisonId(UUID comparisonId);

    /** The whole cohort's pillar rows in one read — the cohort aggregate's input. */
    List<FounderComparisonPillar> findByComparisonIdIn(Collection<UUID> comparisonIds);

    /**
     * Whether anyone in THIS org's slice of the cohort is measured on the
     * distance pillar on both sides — the precondition for annotating it. One
     * {@code SELECT} rather than hydrating every pillar row of the cohort.
     */
    @Query("""
            SELECT COUNT(p) > 0 FROM FounderComparisonPillar p
             WHERE p.distancePillarId = :pillarId
               AND p.state = com.bvisionry.comparison.domain.PillarComparisonState.MAPPED
               AND p.comparisonId IN (SELECT c.id FROM FounderComparison c
                                       WHERE c.orgId = :orgId AND c.cohortId = :cohortId)
            """)
    boolean existsMappedInCohort(@Param("orgId") UUID orgId, @Param("cohortId") UUID cohortId,
                                 @Param("pillarId") UUID pillarId);

    void deleteByComparisonId(UUID comparisonId);
}
