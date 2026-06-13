package com.bvisionry.organization.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record BulkMemberIdsRequest(
        @NotEmpty(message = "At least one member is required")
        List<UUID> memberIds
) {}
