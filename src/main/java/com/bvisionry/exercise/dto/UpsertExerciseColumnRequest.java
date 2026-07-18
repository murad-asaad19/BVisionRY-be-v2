package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseColumnType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpsertExerciseColumnRequest(
        @NotBlank(message = "Column name is required")
        @Size(max = 255, message = "Column name must be 255 characters or less")
        String name,

        String description,

        @NotNull(message = "Column type is required")
        ExerciseColumnType type,

        Map<String, Object> configJson,

        Boolean isRequired
) {
    public UpsertExerciseColumnRequest {
        isRequired = isRequired != null && isRequired;
    }
}
