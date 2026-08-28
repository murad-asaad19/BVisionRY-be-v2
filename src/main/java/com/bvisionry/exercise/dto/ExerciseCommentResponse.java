package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseComment;
import com.bvisionry.exercise.entity.ExerciseCommentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Flat comment view — the client groups replies under {@code parentId} and
 * anchors threads by (rowId, columnId, entryId).
 */
public record ExerciseCommentResponse(
        UUID id,
        UUID rowId,
        UUID columnId,
        /** WORKSHEET anchor: the commented block, or null. */
        UUID blockId,
        String entryId,
        UUID parentId,
        UUID authorId,
        String authorName,
        boolean byAdmin,
        String body,
        String cellValueSnapshot,
        ExerciseCommentStatus status,
        Instant resolvedAt,
        Instant createdAt
) {
    public static ExerciseCommentResponse from(ExerciseComment comment, boolean byAdmin) {
        return new ExerciseCommentResponse(
                comment.getId(),
                comment.getRow() != null ? comment.getRow().getId() : null,
                comment.getColumn() != null ? comment.getColumn().getId() : null,
                comment.getBlockId(),
                comment.getEntryId(),
                comment.getParent() != null ? comment.getParent().getId() : null,
                comment.getAuthor().getId(),
                comment.getAuthor().getName(),
                byAdmin,
                comment.getBody(),
                comment.getCellValueSnapshot(),
                comment.getStatus(),
                comment.getResolvedAt(),
                comment.getCreatedAt());
    }
}
