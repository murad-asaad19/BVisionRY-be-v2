package com.bvisionry.common.pdf.template.dto;

import com.bvisionry.common.pdf.template.PdfTemplateKey;

import java.time.Instant;

public record PdfTemplateSummaryDto(
        PdfTemplateKey key,
        String displayName,
        String description,
        boolean customized,
        Instant updatedAt
) {}
