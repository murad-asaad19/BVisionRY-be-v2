package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Admin root comment. Anchors are optional: rowId + columnId = a cell,
 * columnId only = the whole column, rowId only = the whole row, neither =
 * the submission overall.
 */
public record CreateExerciseCommentRequest(
        UUID rowId,
        UUID columnId,

        @NotBlank(message = "Comment body is required")
        String body
) {}
