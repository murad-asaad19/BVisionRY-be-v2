package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record StartTrialRequest(
        @Min(value = 1, message = "durationDays must be at least 1")
        @Max(value = 365, message = "durationDays must be at most 365")
        Integer durationDays
) {
    public int durationDaysOrDefault() {
        return durationDays == null ? 7 : durationDays;
    }
}
