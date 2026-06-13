package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PipelineStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static PipelineStatus fromValue(String value) {
        return PipelineStatus.valueOf(value.toUpperCase());
    }
}
