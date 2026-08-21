package com.bvisionry.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        // Same bound as LoginRequest — the third anonymous-reachable auth DTO carrying
        // an otherwise unbounded address, here straight into a persisted column.
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        @NotBlank(message = "Name is required")
        String name,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password
) {}
