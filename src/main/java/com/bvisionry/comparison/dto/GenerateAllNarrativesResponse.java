package com.bvisionry.comparison.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of the staff "Generate narratives" (all remaining pillars) action.
 * One model call per pillar, so the counts are the honest summary of a batch
 * that may partially succeed.
 *
 * @param generated pillars that now carry a fresh draft (or approved, under
 *                  the §6 auto-approve toggle).
 * @param skipped   pillars a CONCURRENT request narrated first (the V169
 *                  unique row already existed by the time this call saved) —
 *                  nothing was lost, somebody else's draft is there.
 * @param failed    pillars whose output failed the guardrails twice; nothing
 *                  was persisted for them and the pillar stays generatable.
 * @param outcomes  the per-pillar verdicts, in generation order (biggest
 *                  absolute shift first). Pillars that already had a narrative
 *                  BEFORE the call are not listed — they were never candidates.
 */
public record GenerateAllNarrativesResponse(
        int generated,
        int skipped,
        int failed,
        List<NarrativePillarOutcome> outcomes) {

    /**
     * {@code outcome}: {@code GENERATED} | {@code SKIPPED} | {@code FAILED}.
     * {@code reason} is a human-readable failure explanation, null unless FAILED.
     */
    public record NarrativePillarOutcome(UUID distancePillarId, String pillarName, String outcome,
                                         String reason) {
    }
}
