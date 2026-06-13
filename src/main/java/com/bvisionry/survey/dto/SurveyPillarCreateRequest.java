package com.bvisionry.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SurveyPillarCreateRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255)
        String name,

        String description
) {}
