package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.util.List;

import com.bvisionry.programflow.domain.ProgramTaskStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Full task-builder save: name, deadline, publish state, AI-draft flag and the
 * complete ordered field list (replaces the previous list; existing field ids
 * are preserved).
 */
public record UpdateTaskRequest(
        @NotBlank @Size(max = 200) String name,
        LocalDate dueDate,
        @NotNull ProgramTaskStatus status,
        boolean aiDraft,
        @NotNull @Valid List<FieldUpsert> fields) {
}
