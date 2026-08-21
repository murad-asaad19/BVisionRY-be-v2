package com.bvisionry.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code pillarThreshold} is {@code @NotNull Integer}, NOT a primitive
 * {@code int}: a primitive binds an absent field to {@code 0}, which passes
 * {@code @Min(0)} AND is the off switch for the needs-attention pillar rule
 * ({@code ProgramAdminService.getMatrix}), so an omitted field would silently
 * shut the rule down platform-wide — the exact hazard
 * {@link com.bvisionry.organization.dto.NudgeSettingsDto} documents. The other
 * fields stay primitive: their {@code @Min(1)} already rejects the value
 * absence produces, and widening them is churn.
 */
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
        int onboardingStalledHours,

        @NotNull(message = "pillarThreshold is required")
        @Min(value = 0, message = "pillarThreshold must be at least 0")
        @Max(value = 100, message = "pillarThreshold must be at most 100")
        Integer pillarThreshold
) {
}
