package com.bvisionry.survey.dto;

import java.util.UUID;

public record SurveySubmitResponseDto(
        UUID responseId,
        String thankYouMessage
) {}
