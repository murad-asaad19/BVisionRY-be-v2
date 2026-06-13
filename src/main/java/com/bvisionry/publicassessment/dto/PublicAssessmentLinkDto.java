package com.bvisionry.publicassessment.dto;

import com.bvisionry.publicassessment.entity.PublicAssessmentLinkStatus;
import com.bvisionry.survey.entity.RespondentFieldMode;

import java.time.Instant;
import java.util.UUID;

/** Admin-facing view of a public assessment link. */
public record PublicAssessmentLinkDto(
        UUID id,
        UUID token,
        UUID pipelineId,
        String pipelineName,
        String title,
        String description,
        PublicAssessmentLinkStatus status,
        RespondentFieldMode respondentEmailMode,
        RespondentFieldMode respondentNameMode,
        boolean showResultsToRespondent,
        Integer maxResponses,
        int responseCount,
        Instant createdAt
) {}
