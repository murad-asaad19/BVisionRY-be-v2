package com.bvisionry.insights.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InsightListResponse(
        List<InsightSummary> reports
) {
    public record InsightSummary(
            UUID reportId,
            UUID pipelineId,
            String pipelineName,
            String aiModelUsed,
            Instant generatedAt
    ) {}
}
