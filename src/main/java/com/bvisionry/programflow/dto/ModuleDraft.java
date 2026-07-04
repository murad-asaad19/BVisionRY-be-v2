package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.bvisionry.programflow.domain.FieldType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An AI-composed module draft: streamed to the composer panel as the SSE
 * {@code draft} event, and posted back (with unchecked tasks removed) as the
 * "Add to board" request. Tasks land as {@code DRAFT} + {@code aiDraft}.
 */
public record ModuleDraft(
        @NotBlank @Size(max = 200) String name,
        String summary,
        @NotEmpty @Valid List<DraftTask> tasks) {

    public record DraftTask(
            @NotBlank @Size(max = 200) String name,
            LocalDate dueDate,
            @NotEmpty @Valid List<DraftField> fields) {
    }

    public record DraftField(
            @NotNull FieldType type,
            boolean required,
            Map<String, Object> config) {

        public DraftField {
            config = config == null ? Map.of() : config;
        }
    }
}
