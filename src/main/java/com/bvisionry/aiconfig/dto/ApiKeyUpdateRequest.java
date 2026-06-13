package com.bvisionry.aiconfig.dto;

import com.bvisionry.common.enums.AIProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One record updates the key for a single provider. Providers' keys are stored
 * independently so switching provider in the admin panel doesn't require
 * re-pasting the key.
 */
public record ApiKeyUpdateRequest(
        @NotNull(message = "Provider is required")
        AIProvider provider,

        @NotBlank(message = "API key is required")
        String apiKey
) {}
