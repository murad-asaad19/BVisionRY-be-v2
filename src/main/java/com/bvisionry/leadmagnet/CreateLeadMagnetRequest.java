package com.bvisionry.leadmagnet;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/lead-magnet (the "science behind the 11 pillars"
 * modal). Public and CSRF-exempt, so every field is length-capped.
 */
public record CreateLeadMagnetRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 320, message = "Email must be at most 320 characters")
        String email,

        /** Optional: identifies which surface submitted this lead. */
        @Size(max = 120, message = "Source must be at most 120 characters")
        String source
) {
}
