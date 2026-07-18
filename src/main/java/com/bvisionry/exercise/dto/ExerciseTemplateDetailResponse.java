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
        /** True once the template has any assignment — column add/delete is frozen. */
        boolean structureLocked,
        Instant createdAt,
        Instant updatedAt
) {
    public static ExerciseTemplateDetailResponse from(ExerciseTemplate template, boolean structureLocked) {
        return new ExerciseTemplateDetailResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getStatus(),
                template.getColumns().stream().map(ExerciseColumnResponse::from).toList(),
                structureLocked,
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
