package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.SurveyQuestionType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SurveyAnswerDetailDto(
        UUID questionId,
        UUID pillarId,
        String pillarName,
        String promptText,
        SurveyQuestionType type,
        String responseText,
        String selectedValue,
        List<String> selectedValues,
        BigDecimal numericValue,
        List<String> likertLabels
) {}
