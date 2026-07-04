package com.bvisionry.programflow.dto;

import java.util.Map;
import java.util.UUID;

import com.bvisionry.programflow.domain.FieldType;

/** One form field / player step of a task. */
public record FieldDto(
        UUID id,
        FieldType type,
        boolean required,
        int position,
        Map<String, Object> config) {
}
