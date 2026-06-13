package com.bvisionry.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AttentionThresholdsRequest(
        @Min(value = 1, message = "suspendedDays must be at least 1")
        @Max(value = 365, message = "suspendedDays must be at most 365")
        int suspendedDays,

        @Min(value = 1, message = "trialExpiryWindowDays must be at least 1")
        @Max(value = 365, message = "trialExpiryWindowDays must be at most 365")
        int trialExpiryWindowDays,

        @Min(value = 1, message = "trialJustExpiredWindowDays must be at least 1")
        @Max(value = 365, message = "trialJustExpiredWindowDays must be at most 365")
        int trialJustExpiredWindowDays,

        @Min(value = 1, message = "idleDays must be at least 1")
        @Max(value = 90, message = "idleDays must be at most 90")
        int idleDays,

        @Min(value = 1, message = "onboardingStalledHours must be at least 1")
        @Max(value = 168, message = "onboardingStalledHours must be at most 168")
        int onboardingStalledHours
) {
}
