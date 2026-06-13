package com.bvisionry.organization.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MoveMemberRequest(
        @NotNull(message = "Target organization id is required")
        UUID targetOrganizationId
) {}
