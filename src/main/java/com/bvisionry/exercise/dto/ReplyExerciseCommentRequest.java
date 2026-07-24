package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotBlank;

public record ReplyExerciseCommentRequest(
        @NotBlank(message = "Reply body is required")
        String body
) {}
