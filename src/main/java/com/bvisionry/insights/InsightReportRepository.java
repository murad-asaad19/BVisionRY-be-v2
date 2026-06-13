package com.bvisionry.insights;

import com.bvisionry.common.enums.InsightReportStatus;
import com.bvisionry.insights.entity.InsightReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsightReportRepository extends JpaRepository<InsightReport, UUID> {

    List<InsightReport> findByOrganizationIdOrderByGeneratedAtDesc(UUID organizationId);

    List<InsightReport> findByOrganizationIdAndStatusOrderByGeneratedAtDesc(UUID organizationId, InsightReportStatus status);

    Optional<InsightReport> findTopByOrganizationIdAndPipelineIdOrderByGeneratedAtDesc(UUID organizationId, UUID pipelineId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    long deleteByOrganizationId(UUID organizationId);
}
