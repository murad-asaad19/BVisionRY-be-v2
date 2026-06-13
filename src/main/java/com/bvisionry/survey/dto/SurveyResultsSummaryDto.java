package com.bvisionry.survey.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SurveyResultsSummaryDto(
        UUID surveyId,
        String surveyName,
        long totalResponses,
        Instant firstResponseAt,
        Instant lastResponseAt,
        List<PillarSummaryDto> pillarSummaries
) {}
