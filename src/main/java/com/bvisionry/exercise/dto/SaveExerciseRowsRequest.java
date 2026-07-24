package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Replace-all save of the member's sheet, in display order. */
public record SaveExerciseRowsRequest(
        @NotNull(message = "rows is required")
        List<ExerciseRowPayload> rows
) {}
