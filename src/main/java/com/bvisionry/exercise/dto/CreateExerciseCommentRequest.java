package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Admin comment. Anchors are optional: rowId + columnId = a cell, columnId
 * only = the whole column, rowId only = the whole row, neither = the
 * submission overall. When {@code parentId} is set the comment is a reply
 * under that root thread instead (anchors are inherited and must be omitted).
 */
public record CreateExerciseCommentRequest(
        UUID rowId,
        UUID columnId,
        UUID parentId,

        @NotBlank(message = "Comment body is required")
        String body
) {}
