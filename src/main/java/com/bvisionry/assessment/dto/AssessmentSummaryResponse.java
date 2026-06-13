package com.bvisionry.assessment.dto;

import com.bvisionry.common.enums.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

public record AssessmentSummaryResponse(
        UUID submissionId,
        UUID assignmentId,
        UUID pipelineId,
        String pipelineName,
        String pipelineDescription,
        SubmissionStatus status,
        Instant deadline,
        int totalQuestions,
        int answeredQuestions,
        Instant startedAt,
        Instant submittedAt,
        Instant evaluatedAt,
        /** 1-indexed position of this submission among all check-ins on the same assignment. */
        int checkInNumber,
        /** Cap configured on the assignment — total check-ins the member may complete. */
        int maxCheckIns,
        /** True when this submission is the most recent check-in for its assignment. */
        boolean isLatest
) {}
