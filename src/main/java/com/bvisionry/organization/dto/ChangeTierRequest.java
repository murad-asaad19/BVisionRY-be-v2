package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.SubscriptionTier;
import jakarta.validation.constraints.NotNull;

public record ChangeTierRequest(
        @NotNull(message = "Subscription tier is required")
        SubscriptionTier tier
) {}
