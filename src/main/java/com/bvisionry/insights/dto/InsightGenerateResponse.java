package com.bvisionry.insights.dto;

import com.bvisionry.common.enums.InsightReportStatus;

import java.util.UUID;

public record InsightGenerateResponse(
        UUID reportId,
        InsightReportStatus status
) {}
