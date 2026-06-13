package com.bvisionry.reporting.dto;

import java.math.BigDecimal;

public record CompletionStatsResponse(
        int totalAssigned,
        int inProgress,
        int submitted,
        int evaluated,
        BigDecimal completionRate
) {}
