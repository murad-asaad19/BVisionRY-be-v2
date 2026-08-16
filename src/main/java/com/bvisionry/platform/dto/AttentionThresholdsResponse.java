package com.bvisionry.platform.dto;

public record AttentionThresholdsResponse(
        int suspendedDays,
        int trialExpiryWindowDays,
        int trialJustExpiredWindowDays,
        int idleDays,
        int onboardingStalledHours,
        /** Needs-attention "pillar under threshold" cutoff (%) on the cohort board (spec §11). */
        int pillarThreshold
) {
}
