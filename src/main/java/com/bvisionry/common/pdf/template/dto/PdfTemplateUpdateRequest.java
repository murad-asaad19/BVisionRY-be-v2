package com.bvisionry.common.pdf.template.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record PdfTemplateUpdateRequest(
        @NotNull Map<String, String> values
) {}
