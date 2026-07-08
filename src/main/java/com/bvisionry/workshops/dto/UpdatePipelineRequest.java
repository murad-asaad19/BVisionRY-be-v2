package com.bvisionry.workshops.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bvisionry.workshops.domain.WorkshopTaskAssignee;
import com.bvisionry.workshops.domain.WorkshopTaskType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The builder's exercise-level "Save changes": the whole task pipeline in its
 * new order. {@code id} null = a task added in the draft (created on save);
 * existing tasks missing from the list are deleted.
 */
public record UpdatePipelineRequest(@NotNull @Valid List<TaskSpec> tasks) {

    public record TaskSpec(
            UUID id,
            @NotNull WorkshopTaskType type,
            @NotNull WorkshopTaskAssignee assignee,
            @NotBlank @Size(max = 200) String title,
            Map<String, Object> config) {
    }
}
