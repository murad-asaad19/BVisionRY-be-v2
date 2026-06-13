package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.UserStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeMemberStatusRequest(
        @NotNull(message = "Status is required")
        UserStatus status
) {}
