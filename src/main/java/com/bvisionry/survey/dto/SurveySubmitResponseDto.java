package com.bvisionry.survey.dto;

import java.util.UUID;

public record SurveySubmitResponseDto(
        UUID responseId,
        String thankYouMessage,
        /**
         * Absolute gift-assessment link when the survey gifted a public assessment
         * and the respondent left an email (public link flow only); {@code null}
         * otherwise. The same link is also emailed, but returning it lets the
         * client offer a direct "start your assessment" path so the gift stays
         * reachable even if the async email never arrives. Always null for the
         * authenticated post-assessment flow.
         */
        String giftAssessmentUrl
) {}
