package com.bvisionry.reporting.dto;

import java.util.List;
import java.util.UUID;

public record ScoreDistributionResponse(
        List<PillarDistribution> pillars
) {
    public record PillarDistribution(
            UUID pillarId,
            String pillarName,
            List<HistogramBucket> buckets
    ) {}
}
