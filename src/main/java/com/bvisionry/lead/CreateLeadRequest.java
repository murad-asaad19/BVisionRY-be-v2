package com.bvisionry.lead;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/leads (Book-a-Demo form submission).
 * cohortSize and source are optional.
 *
 * <p>This endpoint is public and CSRF-exempt, so every field is length-capped
 * via {@link Size}. Without these bounds a bot could persist multi-MB rows into
 * the uncapped {@code message} TEXT column and blow up the notification emails.
 */
public record CreateLeadRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must be at most 200 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 320, message = "Email must be at most 320 characters")
        String email,

        @NotBlank(message = "Organization is required")
        @Size(max = 200, message = "Organization must be at most 200 characters")
        String organization,

        @NotBlank(message = "Role is required")
        @Size(max = 120, message = "Role must be at most 120 characters")
        String role,

        @NotBlank(message = "Program type is required")
        @Size(max = 120, message = "Program type must be at most 120 characters")
        String programType,

        /** Optional: cohort / team size bucket. */
        @Size(max = 120, message = "Cohort size must be at most 120 characters")
        String cohortSize,

        @NotBlank(message = "Message is required")
        @Size(max = 5000, message = "Message must be at most 5000 characters")
        String message,

        /** Optional: identifies which surface submitted this lead. */
        @Size(max = 120, message = "Source must be at most 120 characters")
        String source
) {
}
