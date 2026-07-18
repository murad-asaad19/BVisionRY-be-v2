package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseRow;

import java.util.Map;
import java.util.UUID;

public record ExerciseRowResponse(
        UUID id,
        int displayOrder,
        Map<String, Object> cells,
        boolean deleted
) {
    public static ExerciseRowResponse from(ExerciseRow row) {
        return new ExerciseRowResponse(
                row.getId(), row.getDisplayOrder(), row.getCells(), row.isDeleted());
    }
}
