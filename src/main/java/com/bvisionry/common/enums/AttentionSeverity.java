package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Severity bucket for {@code AttentionItem}s surfaced on the platform-admin
 * dashboard. Ordered most-urgent first; {@link com.bvisionry.organization.AttentionRuleService}
 * uses the declared order to sort items.
 */
public enum AttentionSeverity {
    CRITICAL,
    WARNING,
    INFO;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static AttentionSeverity fromValue(String value) {
        return AttentionSeverity.valueOf(value.toUpperCase());
    }
}
