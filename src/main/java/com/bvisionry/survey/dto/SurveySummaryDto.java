package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;

import java.time.Instant;
import java.util.UUID;

public record SurveySummaryDto(
        UUID id,
        String name,
        String description,
        SurveyStatus status,
        SurveyVisibility visibility,
        UUID publicToken,
        Instant publishedAt,
        Instant closedAt,
        int pillarCount,
        long responseCount,
        Instant createdAt,
        Instant updatedAt
) {}
