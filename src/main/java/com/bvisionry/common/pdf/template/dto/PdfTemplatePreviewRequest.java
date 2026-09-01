package com.bvisionry.common.pdf.template.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * In-flight field values the editor sends for preview rendering.
 */
public record PdfTemplatePreviewRequest(
        @NotNull Map<String, String> values
) {}
