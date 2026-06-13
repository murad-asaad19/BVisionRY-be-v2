package com.bvisionry.pipeline.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PillarResponse(
        UUID id,
        UUID pipelineId,
        String name,
        String description,
        String iconKey,
        BigDecimal weight,
        int displayOrder,
        String type,
        String aiRubricInstructions,
        Map<String, List<Integer>> maturityThresholds,
        List<QuestionResponse> questions,
        Instant createdAt,
        Instant updatedAt
) {}
