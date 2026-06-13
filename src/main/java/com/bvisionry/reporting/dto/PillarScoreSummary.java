package com.bvisionry.reporting.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PillarScoreSummary(
        UUID pillarId,
        String pillarName,
        String iconKey,
        BigDecimal scorePercentage,
        String maturityLabel
) {}
