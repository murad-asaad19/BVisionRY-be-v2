package com.bvisionry.platform.dto;

public record AttentionThresholdsResponse(
        int suspendedDays,
        int trialExpiryWindowDays,
        int trialJustExpiredWindowDays,
        int idleDays,
        int onboardingStalledHours
) {
}
