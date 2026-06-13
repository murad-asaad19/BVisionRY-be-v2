package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Min;

public record GenerateJoinLinkRequest(
        @Min(1) int expiryDays
) {}
