package com.bvisionry.aiconfig.dto;

import com.bvisionry.common.enums.PromptType;

import java.time.Instant;
import java.util.UUID;

/**
 * @param revisionId the current immutable revision of this template's content. Evaluations
 *                   persist this (falling back to {@code id} on not-yet-migrated rows) as their
 *                   prompt provenance, so a stored evaluation resolves to the exact prompt text.
 */
public record PromptTemplateResponse(
        UUID id,
        UUID revisionId,
        PromptType promptType,
        String content,
        Instant createdAt
) {}
