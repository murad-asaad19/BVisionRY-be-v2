package com.bvisionry.programflow.dto;

import java.util.Map;
import java.util.UUID;

import com.bvisionry.programflow.domain.FieldType;

import jakarta.validation.constraints.NotNull;

/**
 * A field in a task-builder save. {@code id} is null for newly added fields;
 * existing ids are preserved so learner answers keyed by field id survive edits.
 */
public record FieldUpsert(
        UUID id,
        @NotNull FieldType type,
        boolean required,
        Map<String, Object> config) {

    public FieldUpsert {
        config = config == null ? Map.of() : config;
    }
}
