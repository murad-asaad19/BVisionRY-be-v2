package com.bvisionry.survey.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SurveyPillarDto(
        UUID id,
        UUID surveyId,
        String name,
        String description,
        int displayOrder,
        boolean liveAnalyticsEnabled,
        List<SurveyQuestionDto> questions,
        Instant createdAt,
        Instant updatedAt
) {}
