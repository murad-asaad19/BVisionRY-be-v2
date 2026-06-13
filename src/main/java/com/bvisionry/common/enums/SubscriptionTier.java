package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SubscriptionTier {
    FREE,
    PREMIUM;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static SubscriptionTier fromValue(String value) {
        return SubscriptionTier.valueOf(value.toUpperCase());
    }
}
