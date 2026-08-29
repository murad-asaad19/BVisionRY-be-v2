package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;

import java.time.Instant;
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
        /**
         * How many places hand this exercise out — orgs, cohort tasks and the
         * public link. Drives the list's "assigned to" button; 0 disables it.
         */
        int placementCount,
        /**
         * False once anything is attached that a delete would take with it —
         * an assignment of any grain, or a public response. The list disables
         * its delete control on this instead of letting the click fail.
         */
        boolean deletable,
        /** Public link open? Drives the list's "Public" chip and QR action. */
        boolean isPublic,
        /** The token behind that chip's link, or null if never made public. */
        UUID publicToken,
        Instant createdAt,
        Instant updatedAt
) {
    public static ExerciseTemplateResponse from(ExerciseTemplate template, int columnCount,
                                                int placementCount, boolean deletable) {
        return new ExerciseTemplateResponse(
                template.getId(),
                template.getName(),
                template.getKind(),
                template.getDescription(),
                template.getStatus(),
                columnCount,
                placementCount,
                deletable,
                template.isPublic(),
                template.getPublicToken(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
