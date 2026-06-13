package com.bvisionry.reporting.dto;

import java.util.List;
import java.util.UUID;

public record DashboardOverviewResponse(
        UUID pipelineId,
        String pipelineName,
        int totalMembers,
        int evaluatedCount,
        List<MemberScoreRow> members
) {}
