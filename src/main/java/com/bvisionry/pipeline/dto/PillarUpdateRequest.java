package com.bvisionry.pipeline.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PillarUpdateRequest(
        @NotBlank(message = "Pillar name is required")
        @Size(max = 255, message = "Pillar name must be at most 255 characters")
        String name,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        String iconKey,

        BigDecimal weight,

        @Size(max = 10000, message = "AI rubric instructions must be at most 10000 characters")
        String aiRubricInstructions,

        Map<String, List<Integer>> maturityThresholds
) {}
