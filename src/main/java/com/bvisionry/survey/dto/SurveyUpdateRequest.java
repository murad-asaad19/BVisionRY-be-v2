package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.SurveyVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SurveyUpdateRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255)
        String name,

        String description,

        @NotNull(message = "Respondent email mode is required")
        RespondentFieldMode respondentEmailMode,

        @NotNull(message = "Respondent name mode is required")
        RespondentFieldMode respondentNameMode,

        @NotNull(message = "Visibility is required")
        SurveyVisibility visibility
) {}
