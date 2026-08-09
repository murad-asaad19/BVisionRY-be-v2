package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.ProgramTaskType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Full task-builder save: name, deadline, publish state, AI-draft flag, the
 * task's type/reference/milestone (typed task spine) and the complete ordered
 * field list (replaces the previous list; existing field ids are preserved).
 * A null {@code taskType} keeps the task's current type (additive contract —
 * pre-spine clients never sent one).
 */
public record UpdateTaskRequest(
        @NotBlank @Size(max = 200) String name,
        LocalDate dueDate,
        @NotNull ProgramTaskStatus status,
        boolean aiDraft,
        ProgramTaskType taskType,
        UUID refId,
        MilestoneRole milestoneRole,
        @NotNull @Valid List<FieldUpsert> fields) {
}
