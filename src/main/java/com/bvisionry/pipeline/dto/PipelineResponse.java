package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.PipelineStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PipelineResponse(
        UUID id,
        String name,
        String description,
        int version,
        PipelineStatus status,
        UUID createdBy,
        String freeTierPrompt,
        String overallSummaryPrompt,
        List<PillarResponse> pillars,
        Instant createdAt,
        Instant updatedAt,
        UUID postCompletionSurveyId,
        String postCompletionExternalUrl,
        String postCompletionLabel
) {}
