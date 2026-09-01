package com.bvisionry.common.pdf.template.dto;

import com.bvisionry.common.pdf.template.PdfTemplateKey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full admin view of a PDF template: its field schema, current effective
 * values (admin override or defaults), the defaults themselves (so the UI can
 * offer "reset to default" per field), and the declared system variables.
 *
 * <p>Nested records carry a {@code Pdf} prefix so their OpenAPI component
 * names never collide with the email-template records of the same shape.
 */
public record PdfTemplateDto(
        PdfTemplateKey key,
        String displayName,
        String description,
        boolean customized,
        Instant updatedAt,
        List<PdfFieldSchema> fields,
        Map<String, String> values,
        Map<String, String> defaultValues,
        List<PdfTemplateVariable> variables
) {
    /** Describes one editable text field of a PDF template. */
    public record PdfFieldSchema(
            String id,
            String label,
            String helpText,
            int maxLength,
            List<String> allowedVariables,
            String defaultValue,
            String section,
            boolean required
    ) {}

    public record PdfTemplateVariable(String name, String description) {}
}
