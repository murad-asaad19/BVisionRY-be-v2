package com.bvisionry.notification.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * In-flight field values the wizard sends for preview rendering.
 */
public record EmailTemplatePreviewRequest(
        @NotNull Map<String, Object> values
) {}
