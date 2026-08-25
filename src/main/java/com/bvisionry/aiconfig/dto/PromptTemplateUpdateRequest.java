package com.bvisionry.aiconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptTemplateUpdateRequest(
        @NotBlank(message = "Prompt content is required")
        @Size(min = 10, max = 15000, message = "Content must be between 10 and 15,000 characters")
        String content
) {}
