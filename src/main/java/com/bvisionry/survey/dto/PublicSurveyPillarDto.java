package com.bvisionry.survey.dto;

import java.util.List;
import java.util.UUID;

public record PublicSurveyPillarDto(
        UUID id,
        String name,
        String description,
        int displayOrder,
        List<PublicSurveyQuestionDto> questions
) {}
