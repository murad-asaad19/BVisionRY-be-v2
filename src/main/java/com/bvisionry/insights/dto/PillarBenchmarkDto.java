package com.bvisionry.insights.dto;

import java.util.UUID;

/**
 * One pillar's benchmark: the selected cohort (null when no cohort was
 * requested) against the org's own distribution and the anonymous
 * platform-wide distribution. Carries no org identifiers of any kind.
 */
public record PillarBenchmarkDto(
        UUID pillarId,
        String pillarName,
        BenchmarkSegmentDto cohort,
        BenchmarkSegmentDto org,
        BenchmarkSegmentDto platform
) {}
