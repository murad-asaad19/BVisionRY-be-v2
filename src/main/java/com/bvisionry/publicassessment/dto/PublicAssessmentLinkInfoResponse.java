package com.bvisionry.publicassessment.dto;

import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.survey.entity.RespondentFieldMode;

import java.util.List;
import java.util.UUID;

/**
 * Anonymous-respondent view of a public assessment link. {@code status} is the
 * computed effective status: {@code MAX_RESPONSES_REACHED} overrides
 * {@code ACTIVE} once the response cap is hit, so the FE can render a friendly
 * terminal message without a separate cap check. The question tree reuses the
 * member assessment shape (answers always null here); it is only populated
 * while the link is effectively ACTIVE so non-takeable links don't expose
 * assessment content.
 */
public record PublicAssessmentLinkInfoResponse(
        UUID linkId,
        UUID token,
        String title,
        String description,
        String pipelineName,
        String status,
        RespondentFieldMode respondentEmailMode,
        RespondentFieldMode respondentNameMode,
        boolean showResultsToRespondent,
        List<AssessmentDetailResponse.PillarSection> pillars
) {}
