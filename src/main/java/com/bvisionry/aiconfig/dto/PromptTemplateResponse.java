package com.bvisionry.aiconfig.dto;

import com.bvisionry.common.enums.PromptType;

import java.time.Instant;
import java.util.UUID;

public record PromptTemplateResponse(
        UUID id,
        PromptType promptType,
        String content,
        Instant createdAt
) {}
