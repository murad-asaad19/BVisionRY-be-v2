package com.bvisionry.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PipelineMetadataUpdateRequest(
        @NotBlank(message = "Pipeline name is required")
        @Size(max = 255, message = "Pipeline name must be at most 255 characters")
        String name,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        @Size(max = 20000, message = "Free tier prompt must be at most 20000 characters")
        String freeTierPrompt,

        @Size(max = 20000, message = "Overall summary prompt must be at most 20000 characters")
        String overallSummaryPrompt
) {}
