package com.bvisionry.reporting.dto;

public record HistogramBucket(
        int rangeStart,
        int rangeEnd,
        String label,
        int count
) {}
