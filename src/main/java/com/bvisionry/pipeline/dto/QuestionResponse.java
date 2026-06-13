package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.QuestionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record QuestionResponse(
        UUID id,
        UUID pillarId,
        QuestionType type,
        String promptText,
        int displayOrder,
        boolean isRequired,
        BigDecimal weight,
        Map<String, Object> configJson,
        String systemKey,
        Instant createdAt,
        Instant updatedAt
) {}
