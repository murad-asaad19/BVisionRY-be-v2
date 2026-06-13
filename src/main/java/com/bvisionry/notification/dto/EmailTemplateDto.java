package com.bvisionry.notification.dto;

import com.bvisionry.notification.entity.EmailTemplateKey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full admin view of an email template: its field schema, current effective
 * values (admin override or defaults), the defaults themselves (so the UI can
 * show "reset to default" per field), and the declared system variables.
 */
public record EmailTemplateDto(
        EmailTemplateKey key,
        String displayName,
        String description,
        boolean customized,
        Instant updatedAt,
        List<FieldSchema> fields,
        Map<String, Object> values,
        Map<String, Object> defaultValues,
        List<TemplateVariable> variables
) {
    /**
     * Describes one editable piece of a template. Text-kind fields use
     * {@code maxLength} + {@code defaultValue}; LIST-kind fields use
     * {@code itemMaxLength} + {@code maxItems} + {@code defaultItems}.
     */
    public record FieldSchema(
            String id,
            String label,
            String helpText,
            String kind,
            int maxLength,
            List<String> allowedVariables,
            String defaultValue,
            int step,
            String stepLabel,
            boolean conditional,
            String sectionKey,
            int itemMaxLength,
            int maxItems,
            List<String> defaultItems
    ) {}

    public record TemplateVariable(String name, String description) {}
}
