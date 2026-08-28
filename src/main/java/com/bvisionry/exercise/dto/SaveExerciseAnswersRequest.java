package com.bvisionry.exercise.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Replace-all save of a WORKSHEET submission's answers (block id → value,
 * value shape per {@link com.bvisionry.exercise.entity.WorksheetBlockType}).
 * The worksheet counterpart of {@link SaveExerciseRowsRequest}.
 */
public record SaveExerciseAnswersRequest(
        @NotNull(message = "answers is required")
        Map<String, Object> answers
) {}
