package com.bvisionry.catalog.embed;

import com.bvisionry.common.enums.SubmissionStatus;

import java.util.UUID;

/**
 * Response DTO for the embedded-assessment player endpoints.
 *
 * <ul>
 *   <li>{@code assigned} — {@code false} when no submission exists yet (GET read-only path
 *       with no creation). All other fields are null when {@code assigned=false}.</li>
 *   <li>{@code pipelineId} — the FRI pipeline this ASSIGNMENT lesson embeds.</li>
 *   <li>{@code pipelineName} — display name for the deep-link label.</li>
 *   <li>{@code submissionId} — the member's (most recent) submission for this pipeline.</li>
 *   <li>{@code status} — current submission status; null when {@code assigned=false}.</li>
 * </ul>
 */
public record AssessmentLinkDto(
        boolean assigned,
        UUID pipelineId,
        String pipelineName,
        UUID submissionId,
        SubmissionStatus status
) {

    /** Convenience factory for the "not assigned" / "no submission" case. */
    public static AssessmentLinkDto notAssigned(UUID pipelineId, String pipelineName) {
        return new AssessmentLinkDto(false, pipelineId, pipelineName, null, null);
    }

    /** Convenience factory for a resolved submission. */
    public static AssessmentLinkDto of(UUID pipelineId, String pipelineName,
                                       UUID submissionId, SubmissionStatus status) {
        return new AssessmentLinkDto(true, pipelineId, pipelineName, submissionId, status);
    }
}
