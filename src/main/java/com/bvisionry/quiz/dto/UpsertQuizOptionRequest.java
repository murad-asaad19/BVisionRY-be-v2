package com.bvisionry.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertQuizOptionRequest(
        @NotBlank @Size(max = 500) String text,
        boolean isCorrect,
        int sequence
) {}
