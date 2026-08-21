package com.bvisionry.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service profile update — deliberately name-only. Email, role, status and
 * type changes are admin operations on {@code /api/users/**}; keeping this DTO
 * to the one field a member may edit means the endpoint cannot silently grow
 * into a privilege surface.
 */
public record UpdateProfileRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name
) {}
