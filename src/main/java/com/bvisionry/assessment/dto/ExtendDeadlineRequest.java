package com.bvisionry.assessment.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ExtendDeadlineRequest(
        @NotNull Instant newDeadline
) {}
