package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExerciseTemplateDetailResponse(
        UUID id,
        String name,
        String description,
        ExerciseTemplateStatus status,
        List<ExerciseColumnResponse> columns,
        Instant createdAt,
        Instant updatedAt
) {
    public static ExerciseTemplateDetailResponse from(ExerciseTemplate template) {
        return new ExerciseTemplateDetailResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getStatus(),
                template.getColumns().stream().map(ExerciseColumnResponse::from).toList(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
