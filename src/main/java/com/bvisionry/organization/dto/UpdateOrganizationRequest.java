package com.bvisionry.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(
        @NotBlank(message = "Organization name is required")
        @Size(max = 255, message = "Name must be 255 characters or less")
        String name,
        @Size(max = 2000, message = "Description must be 2000 characters or less")
        String description
) {}
