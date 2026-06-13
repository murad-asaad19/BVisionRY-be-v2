package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.SurveyVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SurveyCreateRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255)
        String name,

        String description,

        RespondentFieldMode respondentEmailMode,

        RespondentFieldMode respondentNameMode,

        SurveyVisibility visibility
) {}
