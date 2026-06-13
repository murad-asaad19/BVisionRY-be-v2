package com.bvisionry.notification.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * In-flight field values plus the custom destination for an admin test send.
 */
public record EmailTemplateTestSendRequest(
        @NotNull Map<String, Object> values,
        @NotBlank @Email(message = "Invalid email format") String toEmail
) {}
