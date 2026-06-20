package com.bvisionry.survey.dto;

import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.survey.entity.ResponseSource;

import java.time.Instant;
import java.util.UUID;

public record SurveyResponseListItemDto(
        UUID responseId,
        Instant submittedAt,
        ResponseSource source,
        String respondentEmail,
        String respondentName,
        boolean possibleDuplicate,
        /**
         * Status of the gifted assessment this respondent took, resolved via the
         * per-response gift link (not by email), or null when the survey gifts
         * nothing, the respondent left no email, or they haven't started the
         * gifted assessment. EVALUATED means results are viewable via
         * {@code assessmentSubmissionId}.
         */
        SubmissionStatus assessmentStatus,
        UUID assessmentSubmissionId
) {}
