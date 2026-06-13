package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.SurveyQuestionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SurveyQuestionRequest(
        @NotNull(message = "Question type is required")
        SurveyQuestionType type,

        @NotBlank(message = "Prompt text is required")
        String promptText,

        Boolean isRequired,

        Map<String, Object> configJson
) {}
