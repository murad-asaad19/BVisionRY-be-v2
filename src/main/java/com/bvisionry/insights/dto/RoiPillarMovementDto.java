package com.bvisionry.insights.dto;

import java.math.BigDecimal;

/**
 * One pillar's movement for the cohort, on a 0–100 scale.
 *
 * <p>The two averages are PAIRED: both are taken over the same
 * {@code foundersPaired} founders — those measured on this pillar at intake AND
 * at their latest assessment — so {@code delta} is a like-for-like move rather
 * than a change in who happened to be measured. When no founder in the cohort
 * has been measured twice on this pillar, {@code foundersPaired} is 0 and every
 * number is {@code null}: the report says so instead of showing a movement it
 * cannot evidence.
 */
public record RoiPillarMovementDto(
        String pillarName,
        int foundersPaired,
        BigDecimal intakeAverage,
        BigDecimal latestAverage,
        BigDecimal delta,
        MovementDirection direction
) {}
