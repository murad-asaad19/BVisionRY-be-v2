package com.bvisionry.insights.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Request body for generating an Org Insight report. {@code memberIds} is the
 * subset of evaluated members the AI should aggregate over — null or empty
 * means "every evaluated member in the pipeline" (legacy behaviour).
 */
public record InsightGenerateRequest(
        @NotNull UUID pipelineId,
        List<UUID> memberIds
) {}
