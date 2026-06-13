package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.PipelineStatus;
import jakarta.validation.constraints.NotNull;

public record PipelineStatusRequest(
        @NotNull(message = "Target status is required")
        PipelineStatus status
) {}
