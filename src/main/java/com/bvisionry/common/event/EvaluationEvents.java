package com.bvisionry.common.event;

import java.util.Map;
import java.util.UUID;

/**
 * Evaluation domain events. They live in {@code common} so the publishing and
 * consuming slices don't have to import each other (the architecture rules
 * forbid new cross-feature dependencies) — same pattern as
 * {@link ProgramFlowEvents} and {@link WorkshopEvents}.
 */
public final class EvaluationEvents {

    private EvaluationEvents() {
    }

    /**
     * A submission finished evaluating CLEANLY — status {@code EVALUATED}, every
     * pillar and the overall summary produced. Published by the {@code evaluation}
     * slice once the outcome is committed; consumed by the {@code pipeline} slice's
     * auto-enrolment engine (roadmap §7 item 10).
     *
     * <p>Deliberately NOT published for a degraded run ({@code NEEDS_REVIEW}): a
     * submission whose pillars partly failed carries {@code maturityLabel =
     * "Unknown"} on the failed ones, and enrolling a founder off a score the AI
     * never produced is exactly the guess this engine must not make. The retry
     * that succeeds publishes it then.
     *
     * @param submissionId the source evaluation — the third leg of the
     *                     {@code [founder, course, source_evaluation]} idempotency
     *                     key (agent-policy {@code auto_enrolment_idempotency_key})
     * @param founderId    the member who was assessed. Anonymous public (QR-link)
     *                     submissions have no account, so they are never published.
     * @param maturityLabelByPillar the STORED maturity label of every pillar row of
     *                     this submission, read back after the commit. The label is
     *                     the band identity of record; a consumer must never
     *                     re-derive a band from the score (RULING 4 / D-2). Includes
     *                     pillars this run did not touch, so a partial re-edit sees
     *                     the founder's whole picture rather than only what moved.
     */
    public record SubmissionEvaluated(UUID submissionId, UUID founderId,
                                      Map<UUID, String> maturityLabelByPillar) {
    }
}
