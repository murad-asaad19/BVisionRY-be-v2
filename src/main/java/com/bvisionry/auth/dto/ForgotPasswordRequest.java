package com.bvisionry.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Same bound, same reason as {@link LoginRequest}: this address is used verbatim as a
 * rate-limit key ({@code checkPasswordResetLimit("email:" + …)}) on an anonymous
 * endpoint, so it must be length-bounded and not merely format-valid.
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email
) {}
