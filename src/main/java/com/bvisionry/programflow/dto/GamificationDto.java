package com.bvisionry.programflow.dto;

/** Points/streak/level chip. Derived from submissions — never stored. */
public record GamificationDto(int points, int streak, int level) {

    public static final int POINTS_PER_SUBMIT = 40;
    public static final int ON_TIME_BONUS = 15;
    public static final int POINTS_PER_LEVEL = 250;

    public static int levelFor(int points) {
        return points / POINTS_PER_LEVEL + 1;
    }
}
