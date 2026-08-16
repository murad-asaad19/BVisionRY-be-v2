package com.bvisionry.insights.dto;

import java.math.BigDecimal;

/**
 * One segment (cohort / org / platform) of one pillar's benchmark.
 *
 * <p>When {@code sufficient} is false every score field is {@code null} —
 * "insufficient data", never a number ({@code benchmark_min_sample: 30}).
 * {@code sampleSize} is populated for the caller's own cohort/org segments so
 * the UI can name what unlocks the benchmark; for the anonymous platform
 * segment it is {@code null} unless the segment is sufficient
 * ({@code benchmark_anonymity: AGGREGATE_ONLY}).
 */
public record BenchmarkSegmentDto(
        boolean sufficient,
        Integer sampleSize,
        BigDecimal mean,
        BigDecimal p25,
        BigDecimal p75
) {}
