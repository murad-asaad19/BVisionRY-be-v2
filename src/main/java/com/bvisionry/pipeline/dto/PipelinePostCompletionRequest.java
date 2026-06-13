package com.bvisionry.pipeline.dto;

import com.bvisionry.common.validation.ValidExternalUrl;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PipelinePostCompletionRequest(
        UUID surveyId,

        @ValidExternalUrl
        String externalUrl,

        @Size(max = 120)
        String label
) {}
