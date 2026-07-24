package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** List-item view of a template (no columns). */
public record ExerciseTemplateResponse(
        UUID id,
        String name,
        String description,
        ExerciseTemplateStatus status,
        int columnCount,
        List<AssignedOrg> assignedOrganizations,
        Instant createdAt,
        Instant updatedAt
) {
    /** Org holding a provision of this template — the list-view "Assigned to" chips. */
    public record AssignedOrg(UUID id, String name) {}

    public static ExerciseTemplateResponse from(ExerciseTemplate template, int columnCount,
                                                List<AssignedOrg> assignedOrganizations) {
        return new ExerciseTemplateResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getStatus(),
                columnCount,
                assignedOrganizations,
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
