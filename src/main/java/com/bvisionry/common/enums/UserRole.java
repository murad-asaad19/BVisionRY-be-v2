package com.bvisionry.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum UserRole {
    SUPER_ADMIN,
    ORG_ADMIN,
    INSTRUCTOR,
    MANAGER,
    MEMBER;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static UserRole fromValue(String value) {
        return UserRole.valueOf(value.toUpperCase());
    }
}
