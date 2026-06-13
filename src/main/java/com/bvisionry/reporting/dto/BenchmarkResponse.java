package com.bvisionry.reporting.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BenchmarkResponse(
        UUID pipelineId,
        BigDecimal platformOverallAverage,
        BigDecimal teamOverallAverage,
        int teamPercentileRank,
        List<PillarBenchmark> pillarBenchmarks
) {
    public record PillarBenchmark(
            UUID pillarId,
            String pillarName,
            BigDecimal platformAverage,
            BigDecimal teamAverage,
            int teamPercentile
    ) {}
}
