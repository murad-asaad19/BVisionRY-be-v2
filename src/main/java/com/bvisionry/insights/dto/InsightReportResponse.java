package com.bvisionry.insights.dto;

import com.bvisionry.common.enums.InsightReportStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record InsightReportResponse(
        UUID reportId,
        UUID pipelineId,
        String pipelineName,
        Map<String, Object> report,
        String aiModelUsed,
        Instant generatedAt,
        InsightReportStatus status,
        String failureReason
) {}
