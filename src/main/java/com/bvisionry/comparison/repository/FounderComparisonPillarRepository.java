package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.FounderComparisonPillar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FounderComparisonPillarRepository
        extends JpaRepository<FounderComparisonPillar, UUID> {

    List<FounderComparisonPillar> findByComparisonId(UUID comparisonId);

    /** The whole cohort's pillar rows in one read — the cohort aggregate's input. */
    List<FounderComparisonPillar> findByComparisonIdIn(Collection<UUID> comparisonIds);

    void deleteByComparisonId(UUID comparisonId);
}
