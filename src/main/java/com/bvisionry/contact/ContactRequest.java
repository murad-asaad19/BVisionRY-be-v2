package com.bvisionry.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/contact (public "Contact Us" form submission).
 * company is optional.
 *
 * <p>This endpoint is public and CSRF-exempt, so every field is length-capped
 * via {@link Size}. Without these bounds a bot could flood the notification
 * inbox with multi-MB messages.
 */
public record ContactRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must be at most 200 characters")
        String fullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 320, message = "Email must be at most 320 characters")
        String email,

        /** Optional: the sender's company / organization. */
        @Size(max = 200, message = "Company must be at most 200 characters")
        String company,

        @NotBlank(message = "Inquiry type is required")
        @Size(max = 120, message = "Inquiry type must be at most 120 characters")
        String inquiry,

        @NotBlank(message = "Message is required")
        @Size(max = 5000, message = "Message must be at most 5000 characters")
        String message
) {
}
