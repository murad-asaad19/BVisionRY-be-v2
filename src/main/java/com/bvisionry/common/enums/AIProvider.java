package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AIProvider {
    OPENROUTER,
    ANTHROPIC;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static AIProvider fromValue(String value) {
        return AIProvider.valueOf(value.toUpperCase());
    }
}
