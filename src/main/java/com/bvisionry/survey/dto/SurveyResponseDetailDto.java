package com.bvisionry.survey.dto;

import com.bvisionry.survey.entity.ResponseSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SurveyResponseDetailDto(
        UUID responseId,
        Instant submittedAt,
        ResponseSource source,
        String respondentEmail,
        String respondentName,
        List<SurveyAnswerDetailDto> answers
) {}
