package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AICallStatus {
    SUCCESS,
    FAILED;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static AICallStatus fromValue(String value) {
        return AICallStatus.valueOf(value.toUpperCase());
    }
}
