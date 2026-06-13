package com.bvisionry.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SurveyPillarUpdateRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255)
        String name,

        String description
) {}
