package com.bvisionry.survey.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Survey response payload embedded in a member's assessment results so the
 * admin (and the member) can see the post-assessment survey answers next to
 * the evaluation. Answers are pre-sorted by pillar.displayOrder then
 * question.displayOrder.
 */
public record SubmissionSurveyResponseDto(
        UUID responseId,
        UUID surveyId,
        String surveyName,
        Instant submittedAt,
        List<SurveyAnswerDetailDto> answers
) {}
