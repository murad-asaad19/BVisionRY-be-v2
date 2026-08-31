package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.PipelineStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PipelineSummaryResponse(
        UUID id,
        String name,
        String description,
        String abbreviation,
        int version,
        PipelineStatus status,
        UUID createdBy,
        int pillarCount,
        List<AssignedOrgSummary> assignedOrganizations,
        boolean deletable,
        Instant createdAt,
        Instant updatedAt
) {}
