package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.SurveyQuestionType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SurveyQuestionDto(
        UUID id,
        UUID pillarId,
        SurveyQuestionType type,
        String promptText,
        int displayOrder,
        boolean isRequired,
        boolean liveAnalyticsEnabled,
        Map<String, Object> configJson,
        Instant createdAt,
        Instant updatedAt
) {}
