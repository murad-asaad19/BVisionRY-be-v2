package com.bvisionry.publicassessment.dto;

import com.bvisionry.publicassessment.entity.PublicAssessmentLinkStatus;
import com.bvisionry.survey.entity.RespondentFieldMode;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * PATCH-style partial update for a public assessment link. Every field is
 * optional: when null, the existing value is preserved.
 */
public record UpdatePublicAssessmentLinkRequest(
        @Size(max = 255, message = "Title must be at most 255 characters")
        String title,

        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        PublicAssessmentLinkStatus status,

        RespondentFieldMode respondentEmailMode,

        RespondentFieldMode respondentNameMode,

        Boolean showResultsToRespondent,

        @Positive(message = "Max responses must be a positive number")
        Integer maxResponses
) {}
