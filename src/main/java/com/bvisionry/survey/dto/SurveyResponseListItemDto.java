package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.ResponseSource;

import java.time.Instant;
import java.util.UUID;

public record SurveyResponseListItemDto(
        UUID responseId,
        Instant submittedAt,
        ResponseSource source,
        String respondentEmail,
        String respondentName,
        boolean possibleDuplicate
) {}
