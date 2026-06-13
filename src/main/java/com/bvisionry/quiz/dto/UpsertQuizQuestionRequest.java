package com.bvisionry.quiz.dto;

import java.util.List;

import com.bvisionry.quiz.domain.QuestionType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertQuizQuestionRequest(
        @NotNull QuestionType type,
        @NotBlank @Size(max = 5000) String prompt,
        @Min(0) int points,
        int sequence,
        @NotNull @Valid List<UpsertQuizOptionRequest> options
) {}
