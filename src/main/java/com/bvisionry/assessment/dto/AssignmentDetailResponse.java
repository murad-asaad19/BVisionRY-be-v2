package com.bvisionry.assessment.dto;

import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.reporting.dto.PersonalInfoEntry;
import com.bvisionry.survey.dto.SurveySummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin drawer view of a single assignment. Bundles the assignment, its
 * submission timestamps, progress counters (how many questions answered vs.
 * required), and the failure reason (when status == FAILED) so the frontend
 * can render a status-aware detail panel in one call.
 *
 * <p>The post-assessment survey state is bundled into a single
 * {@link SurveySummary} field. {@code null} = no survey paired;
 * non-null + {@code responseId == null} = paired but not yet submitted;
 * non-null + {@code responseId != null} = submitted (timestamp populated).
 *
 * <p>{@code personalInfo} carries the "general information" the member filled
 * in (Personal-pillar answers). Always present — empty list when the pipeline
 * has no Personal pillar or the member hasn't answered any of its questions.
 */
public record AssignmentDetailResponse(
        UUID id,
        UUID pipelineId,
        String pipelineName,
        Integer pipelineVersion,
        UUID organizationId,
        UUID userId,
        String userName,
        String userEmail,
        UUID assignedBy,
        Instant createdAt,
        Instant deadline,
        Instant effectiveDeadline,
        UUID submissionId,
        SubmissionStatus status,
        Instant startedAt,
        Instant submittedAt,
        Instant evaluatedAt,
        int totalQuestions,
        int answeredQuestions,
        String failureReason,
        SurveySummary survey,
        List<PersonalInfoEntry> personalInfo,
        int maxCheckIns,
        int checkInCount
) {}
