package com.bvisionry.comparison.dto;

import java.util.List;
import java.util.UUID;

/**
 * Everything the founder-profile Growth tab's narrative section needs, in one
 * GET (spec §6 review gate).
 *
 * @param narratives             drafts AND approved — this payload is
 *                               staff-only; the member's own report reads
 *                               {@link FounderComparisonDto#narratives()},
 *                               which is approved-only by construction.
 * @param availablePillars       mapped pillars with enough before-text and no
 *                               narrative yet — the options behind "Generate
 *                               narrative for another pillar…".
 * @param insufficientDataPillars mapped pillars whose before-text is missing or
 *                               under the length floor. These deliberately have
 *                               NO row: §6 says emit the standard line and skip
 *                               the model call, and a row would be a narrative
 *                               that was never written. The UI renders
 *                               {@code notEnoughDataSentence} inline against
 *                               each of them, not as a card.
 * @param notEnoughDataSentence  the configured §7 sentence, live (not
 *                               snapshotted — with no row there is nothing to
 *                               snapshot, and the current wording is the honest
 *                               one to show).
 * @param autoApprove            the platform toggle's current state, so the
 *                               review banner can say whether approval is
 *                               actually required.
 * @param comparisonComputedAt   the reviewed comparison's compute timestamp.
 */
public record NarrativeReviewResponse(
        List<ShiftNarrativeDto> narratives,
        List<NarrativePillarOption> availablePillars,
        List<NarrativePillarOption> insufficientDataPillars,
        String notEnoughDataSentence,
        boolean autoApprove,
        /**
         * When the comparison this reviews was last computed. A DRAFT that was
         * generated before it, and never hand-edited since, is prose whose
         * numbers moved underneath it — the review UI says so instead of making
         * a reviewer diff two timestamps by eye.
         */
        java.time.Instant comparisonComputedAt) {

    /**
     * Named {@code NarrativePillarOption}, not {@code PillarOption}: the
     * mapping payload already exports a {@code PillarOption} schema with a
     * different shape, and OpenAPI keys components by SIMPLE name — the
     * collision silently hands the web contract the wrong fields.
     */
    public record NarrativePillarOption(UUID distancePillarId, String pillarName) {
    }
}
