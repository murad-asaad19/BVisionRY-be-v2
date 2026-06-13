package com.bvisionry.notification.schema;

import java.util.List;

/**
 * Describes one admin-editable piece of text inside an email template.
 * The wizard UI renders a form control per field; the renderer substitutes
 * the admin's value (or default) into {{fields.<id>}} in the mustache skeleton.
 */
public record EmailTemplateField(
        String id,
        String label,
        String helpText,
        Kind kind,
        int maxLength,
        List<String> allowedVariables,
        String defaultValue,
        int step,
        String stepLabel,
        boolean conditional,
        String sectionKey,
        // LIST-kind metadata. Ignored for text fields; defaultItems is null for text.
        int itemMaxLength,
        int maxItems,
        List<String> defaultItems
) {
    /**
     * Convenience constructor for text-kind fields — matches the original 11-arg
     * shape used throughout the registry. List-specific slots default to zero/null.
     */
    public EmailTemplateField(String id, String label, String helpText, Kind kind, int maxLength,
                              List<String> allowedVariables, String defaultValue,
                              int step, String stepLabel, boolean conditional, String sectionKey) {
        this(id, label, helpText, kind, maxLength, allowedVariables, defaultValue,
             step, stepLabel, conditional, sectionKey, 0, 0, null);
    }

    /** Factory for LIST-kind fields. */
    public static EmailTemplateField list(String id, String label, String helpText,
                                           int itemMaxLength, int maxItems,
                                           List<String> allowedVariables, List<String> defaultItems,
                                           int step, String stepLabel, boolean conditional, String sectionKey) {
        return new EmailTemplateField(id, label, helpText, Kind.LIST, 0, allowedVariables, "",
                step, stepLabel, conditional, sectionKey, itemMaxLength, maxItems, defaultItems);
    }

    public enum Kind {
        PLAIN_TEXT,
        RICH_TEXT,
        CTA_LABEL,
        /** Repeating list of short text items — rendered as add/remove rows in the wizard. */
        LIST
    }
}
