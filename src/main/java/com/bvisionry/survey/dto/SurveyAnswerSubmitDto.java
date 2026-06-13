package com.bvisionry.survey.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record SurveyAnswerSubmitDto(
        @NotNull(message = "questionId is required")
        UUID questionId,

        String responseText,

        String selectedValue,

        List<String> selectedValues
) {}
