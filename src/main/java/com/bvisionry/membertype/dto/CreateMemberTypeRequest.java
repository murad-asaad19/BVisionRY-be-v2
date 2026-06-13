package com.bvisionry.membertype.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMemberTypeRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 64, message = "Code must be 64 characters or less")
        // Uppercase letters, digits and underscores — matches enum-style codes
        // so they can be used safely in URLs and comparisons.
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                message = "Code must start with a letter and contain only uppercase letters, digits, and underscores")
        String code,

        @NotBlank(message = "Label is required")
        @Size(max = 128, message = "Label must be 128 characters or less")
        String label,

        Integer displayOrder
) {}
