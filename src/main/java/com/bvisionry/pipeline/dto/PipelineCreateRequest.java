package com.bvisionry.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pipeline create request. {@code createdBy} is intentionally absent — the
 * server derives it from the authenticated principal via
 * {@link com.bvisionry.auth.SecurityUtils#getCurrentUserId()} so a client
 * can't ascribe creation to another user.
 */
public record PipelineCreateRequest(
        @NotBlank(message = "Pipeline name is required")
        @Size(max = 255, message = "Pipeline name must be at most 255 characters")
        String name,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        String freeTierPrompt,

        String overallSummaryPrompt
) {}
