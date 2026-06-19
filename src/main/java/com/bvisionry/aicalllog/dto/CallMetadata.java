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

    /**
     * Tags a borderline-confidence re-sample so its audit row is distinguishable from
     * the primary pillar call (and from the other samples). Without this, every
     * discarded escalation sample logged an identical SUCCESS row under the same pillar
     * name, double-counting tokens and hiding which sample was actually chosen.
     *
     * @param sampleIndex 1-based index of the extra sample
     */
    public static CallMetadata forEscalationSample(CallMetadata base, int sampleIndex) {
        String name = base.pillarName() == null ? "escalation" : base.pillarName();
        return new CallMetadata(base.submissionId(), base.pipelineId(),
                name + " [escalation-sample-" + sampleIndex + "]");
    }
}
