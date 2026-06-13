package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SurveyDto(
        UUID id,
        String name,
        String description,
        SurveyStatus status,
        SurveyVisibility visibility,
        UUID publicToken,
        Instant publishedAt,
        Instant closedAt,
        RespondentFieldMode respondentEmailMode,
        RespondentFieldMode respondentNameMode,
        UUID createdBy,
        List<SurveyPillarDto> pillars,
        Instant createdAt,
        Instant updatedAt
) {}
