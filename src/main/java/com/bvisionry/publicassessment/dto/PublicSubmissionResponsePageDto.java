package com.bvisionry.publicassessment.dto;

import com.bvisionry.common.enums.SubmissionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Admin row in a public link's paginated responses table.
 * {@code overallScore} comes from the {@code OverallSummary} when evaluation
 * has produced one; null otherwise.
 */
public record PublicSubmissionResponsePageDto(
        UUID submissionId,
        SubmissionStatus status,
        String respondentEmail,
        String respondentName,
        Instant startedAt,
        Instant submittedAt,
        Instant evaluatedAt,
        BigDecimal overallScore
) {}
