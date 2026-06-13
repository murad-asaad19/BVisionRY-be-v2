package com.bvisionry.reporting.dto;

import com.bvisionry.common.enums.SubmissionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberHistoryResponse(
        List<PipelineHistory> pipelines
) {
    public record PipelineHistory(
            UUID pipelineId,
            String pipelineName,
            List<SubmissionSummary> submissions
    ) {}

    public record SubmissionSummary(
            UUID submissionId,
            BigDecimal overallScore,
            SubmissionStatus status,
            Instant evaluatedAt
    ) {}
}
