package com.bvisionry.programflow.dto;

import com.bvisionry.programflow.domain.CohortStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateCohortRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull CohortStatus status) {
}
