package com.bvisionry.insights.dto;

/**
 * Which way a score moved between intake and latest. Absent (null) whenever
 * there is no delta to describe — a founder measured only once, or a pillar no
 * founder was measured on twice. Never inferred from a single point.
 */
public enum MovementDirection {
    UP,
    DOWN,
    FLAT
}
