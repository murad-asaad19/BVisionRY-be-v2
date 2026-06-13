package com.bvisionry.quiz.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpsertQuizRequest(
        @Min(0) @Max(100) int passingScorePct,
        @Min(0) int maxAttempts,
        boolean shuffle,
        @NotNull @Valid List<UpsertQuizQuestionRequest> questions
) {}
