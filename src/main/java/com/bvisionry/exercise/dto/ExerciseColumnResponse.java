package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseColumnType;

import java.util.Map;
import java.util.UUID;

public record ExerciseColumnResponse(
        UUID id,
        String name,
        String description,
        ExerciseColumnType type,
        Map<String, Object> configJson,
        int displayOrder,
        boolean isRequired,
        boolean isLocked
) {
    public static ExerciseColumnResponse from(ExerciseColumn column) {
        return new ExerciseColumnResponse(
                column.getId(),
                column.getName(),
                column.getDescription(),
                column.getType(),
                column.getConfigJson(),
                column.getDisplayOrder(),
                column.isRequired(),
                column.isLocked());
    }
}
