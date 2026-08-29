package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** List-item view of a template (no columns/blocks). */
public record ExerciseTemplateResponse(
        UUID id,
        String name,
        ExerciseTemplateKind kind,
        String description,
        ExerciseTemplateStatus status,
        /** SHEET: number of columns. WORKSHEET: number of blocks. */
        int columnCount,
        List<AssignedOrg> assignedOrganizations,
        /** Public link open? Drives the list's "Public" chip and QR action. */
        boolean isPublic,
        /** The token behind that chip's link, or null if never made public. */
        UUID publicToken,
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
                template.getKind(),
                template.getDescription(),
                template.getStatus(),
                columnCount,
                assignedOrganizations,
                template.isPublic(),
                template.getPublicToken(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
