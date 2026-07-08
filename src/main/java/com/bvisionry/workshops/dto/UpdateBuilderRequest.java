package com.bvisionry.workshops.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The builder's global "Save changes": the whole workshop pipeline — every
 * exercise in order, each with its ordered task list. {@code id} null = added
 * in the draft (created on save); existing exercises/tasks missing from the
 * lists are deleted. Reuses {@link UpdatePipelineRequest.TaskSpec} for tasks.
 */
public record UpdateBuilderRequest(@NotNull @Valid List<ExerciseSpec> exercises) {

    public record ExerciseSpec(
            UUID id,
            @NotBlank @Size(max = 200) String title,
            @NotNull @Valid List<UpdatePipelineRequest.TaskSpec> tasks) {
    }
}
