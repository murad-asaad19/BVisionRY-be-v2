package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.RespondentFieldMode;

import java.util.List;
import java.util.UUID;

public record PublicSurveyDto(
        UUID id,
        String name,
        String description,
        RespondentFieldMode respondentEmailMode,
        RespondentFieldMode respondentNameMode,
        List<PublicSurveyPillarDto> pillars
) {}
