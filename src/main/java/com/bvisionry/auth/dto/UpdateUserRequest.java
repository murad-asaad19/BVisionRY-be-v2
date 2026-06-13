package com.bvisionry.auth.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 255, message = "Name must be 255 characters or less")
        String name,
        @Size(max = 500, message = "Avatar URL must be 500 characters or less")
        String avatarUrl,
        @Size(max = 64, message = "Member type code must be 64 characters or less")
        String userType
) {}
