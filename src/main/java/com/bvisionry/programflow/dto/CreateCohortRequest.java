package com.bvisionry.programflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCohortRequest(
        @NotBlank @Size(max = 200) String name) {
}
