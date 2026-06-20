package com.bvisionry.survey.dto;

import java.util.List;
import java.util.UUID;

public record SurveyResponsePageDto(
        List<SurveyResponseListItemDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        /**
         * The public assessment link this survey gifts, or null when none is
         * configured. When present, the client renders the "Assessment" column
         * and builds per-respondent results links against this id.
         */
        UUID giftAssessmentLinkId
) {}
