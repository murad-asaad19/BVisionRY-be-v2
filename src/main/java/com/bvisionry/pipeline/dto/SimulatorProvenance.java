package com.bvisionry.pipeline.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Admin-only; kept separate from member-facing DTOs so it is never leaked. */
public record SimulatorProvenance(
        String model,
        BigDecimal temperature,
        UUID systemPromptVersionId
) {}
