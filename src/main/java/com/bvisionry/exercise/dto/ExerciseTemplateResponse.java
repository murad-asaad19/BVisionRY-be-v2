package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;

import java.time.Instant;
import java.util.UUID;

/** List-item view of a template (no columns). */
public record ExerciseTemplateResponse(
        UUID id,
        String name,
        String description,
        ExerciseTemplateStatus status,
        int columnCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static ExerciseTemplateResponse from(ExerciseTemplate template, int columnCount) {
        return new ExerciseTemplateResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getStatus(),
                columnCount,
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
