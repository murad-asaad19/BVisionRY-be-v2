package com.bvisionry.organization.dto;

import java.time.Instant;

public record JoinLinkInfoResponse(
        String organizationName, boolean isValid, boolean isExpired, Instant expiresAt
) {}
