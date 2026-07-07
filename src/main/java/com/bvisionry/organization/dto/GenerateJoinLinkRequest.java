package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Min;

import java.util.UUID;

public record GenerateJoinLinkRequest(
        @Min(1) int expiryDays,
        /** When set, the link is workshop-bound: joiners are team-assigned there. */
        UUID workshopId
) {}
