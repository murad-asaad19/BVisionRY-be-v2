package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.SurveyStatus;
import jakarta.validation.constraints.NotNull;

public record SurveyStatusRequest(
        @NotNull(message = "Status is required")
        SurveyStatus status
) {}
