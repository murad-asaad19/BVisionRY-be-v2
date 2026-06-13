package com.bvisionry.assessment.dto;

import com.bvisionry.common.enums.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

public record SubmissionStatusResponse(
        UUID submissionId,
        SubmissionStatus status,
        Instant submittedAt,
        Instant evaluatedAt
) {}
