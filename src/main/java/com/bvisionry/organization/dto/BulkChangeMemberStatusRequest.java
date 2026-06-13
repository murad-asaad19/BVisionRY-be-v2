package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.UserStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record BulkChangeMemberStatusRequest(
        @NotEmpty(message = "At least one member is required")
        List<UUID> memberIds,
        @NotNull(message = "Status is required")
        UserStatus status
) {}
