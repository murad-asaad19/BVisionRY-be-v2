package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.FounderComparisonPillar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FounderComparisonPillarRepository
        extends JpaRepository<FounderComparisonPillar, UUID> {

    List<FounderComparisonPillar> findByComparisonId(UUID comparisonId);

    void deleteByComparisonId(UUID comparisonId);
}
