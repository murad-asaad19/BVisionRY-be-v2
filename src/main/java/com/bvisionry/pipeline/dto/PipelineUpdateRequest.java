package com.bvisionry.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PipelineUpdateRequest(
        @NotBlank(message = "Pipeline name is required")
        @Size(max = 255, message = "Pipeline name must be at most 255 characters")
        String name,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        String freeTierPrompt,

        String overallSummaryPrompt
) {}
