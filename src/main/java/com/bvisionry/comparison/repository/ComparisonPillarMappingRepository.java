package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.ComparisonPillarMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComparisonPillarMappingRepository
        extends JpaRepository<ComparisonPillarMapping, UUID> {

    List<ComparisonPillarMapping> findByCohortIdAndBaselinePipelineIdAndDistancePipelineId(
            UUID cohortId, UUID baselinePipelineId, UUID distancePipelineId);

    boolean existsByCohortIdAndBaselinePipelineIdAndDistancePipelineId(
            UUID cohortId, UUID baselinePipelineId, UUID distancePipelineId);

    Optional<ComparisonPillarMapping>
            findByCohortIdAndBaselinePipelineIdAndDistancePipelineIdAndBaselinePillarId(
                    UUID cohortId, UUID baselinePipelineId, UUID distancePipelineId,
                    UUID baselinePillarId);

    Optional<ComparisonPillarMapping>
            findByCohortIdAndBaselinePipelineIdAndDistancePipelineIdAndDistancePillarId(
                    UUID cohortId, UUID baselinePipelineId, UUID distancePipelineId,
                    UUID distancePillarId);
}
