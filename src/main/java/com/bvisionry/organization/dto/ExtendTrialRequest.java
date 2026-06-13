package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ExtendTrialRequest(
        @NotNull(message = "additionalDays is required")
        @Min(value = 1, message = "additionalDays must be at least 1")
        @Max(value = 365, message = "additionalDays must be at most 365")
        Integer additionalDays
) {}
