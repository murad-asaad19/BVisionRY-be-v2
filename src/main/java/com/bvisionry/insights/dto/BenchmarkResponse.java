package com.bvisionry.insights.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-pillar benchmark comparison for one pipeline. {@code cohortId}/
 * {@code cohortName} echo the requested cohort (both null when comparing the
 * whole org). {@code minSample} is the suppression floor the client renders
 * in its "insufficient data" copy.
 */
public record BenchmarkResponse(
        int minSample,
        UUID pipelineId,
        UUID cohortId,
        String cohortName,
        List<PillarBenchmarkDto> pillars
) {}
