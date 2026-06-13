package com.bvisionry.aicalllog.dto;

import java.util.UUID;

/**
 * Correlation context that travels with every AI call so the logger can tie
 * the persisted row back to a specific pipeline, submission, and pillar. All
 * fields are nullable — {@link #NONE} covers ad-hoc calls (e.g. team
 * insights, test harnesses) that have no submission context.
 */
public record CallMetadata(
        UUID submissionId,
        UUID pipelineId,
        String pillarName
) {
    public static final CallMetadata NONE = new CallMetadata(null, null, null);

    public static CallMetadata forPillar(UUID submissionId, UUID pipelineId, String pillarName) {
        return new CallMetadata(submissionId, pipelineId, pillarName);
    }

    public static CallMetadata forSummary(UUID submissionId, UUID pipelineId) {
        return new CallMetadata(submissionId, pipelineId, null);
    }
}
