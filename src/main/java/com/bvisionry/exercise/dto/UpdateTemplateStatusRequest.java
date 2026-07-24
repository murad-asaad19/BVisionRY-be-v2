package com.bvisionry.exercise.dto;

import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateTemplateStatusRequest(
        @NotNull(message = "Status is required")
        ExerciseTemplateStatus status
) {}
