package com.bvisionry.pipeline.dto;

import com.bvisionry.common.enums.QuestionType;

import java.util.Map;
import java.util.UUID;

public record QuestionPreviewResponse(
        UUID id,
        QuestionType type,
        String promptText,
        int displayOrder,
        boolean isRequired,
        Map<String, Object> configJson
) {}
