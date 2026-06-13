package com.bvisionry.assessment.dto;

import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.pipeline.dto.PostCompletionLinkDto;

import java.time.Instant;
import java.util.UUID;

public record SubmitAssessmentResponse(
        UUID submissionId,
        SubmissionStatus status,
        Instant submittedAt,
        String message,
        PostCompletionLinkDto postCompletion
) {}
