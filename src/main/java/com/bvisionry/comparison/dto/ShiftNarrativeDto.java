package com.bvisionry.comparison.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One shift narrative as the review UI and the member report read it (spec §6).
 *
 * <p>§7b: generated / approved / edited each carry their own timestamp AND the
 * actor's name, so the founder profile can stamp "Approved Oct 3 · Test Org
 * Admin" without a second lookup. Actor names go null after that reviewer's
 * GDPR erasure (the FK is SET NULL) — the same stance as the cohort launch
 * ledger.
 *
 * <p>{@code closingAction} is separate from the prose so the member card can
 * render the mandatory "Next step:" footer on a decline without string-slicing
 * it (which is exactly what the design prototype had to do).
 *
 * <p><strong>Two shapes, forever.</strong> {@code items} is the breakdown
 * (spec §2); {@code kind} + {@code body} are the pre-V189 single-paragraph
 * form, still carried by rows generated before it. Exactly one side is
 * populated on any row, so a reader falls back: {@code items ?? [{kind,
 * body}]}.
 */
public record ShiftNarrativeDto(
        UUID id,
        UUID baselinePillarId,
        UUID distancePillarId,
        String pillarName,
        /** The observation breakdown — null on a legacy row (read {@code kind}/{@code body}). */
        List<NarrativeItemDto> items,
        /** Legacy single classification — null on everything written since V189. */
        String kind,
        /** Legacy single-paragraph prose — null on everything written since V189. */
        String body,
        String closingAction,
        /** Declining pillar (delta &lt; 0) — the structural fact §6's close rule keys on. */
        boolean decline,
        /**
         * The pillar this narrative describes is no longer part of the current
         * comparison (a super admin remapped the pair). Staff still see it, so
         * they can read and delete it; the member never does.
         */
        boolean detached,
        /** Band label/key at generation — DISPLAY ONLY, never a guard. */
        String bandKey,
        /**
         * The "nothing disappears" audit came back short and code-synthesised
         * fallback items were appended (second reading redesign) — the review
         * UI badges these so a coach checks them by hand. Always DRAFT when
         * freshly generated, even under auto-approve.
         */
        boolean coverageGap,
        String status,
        Instant generatedAt,
        UUID approvedBy,
        String approvedByName,
        Instant approvedAt,
        UUID editedBy,
        String editedByName,
        Instant editedAt) {

    /**
     * One observation of the breakdown: one {@code NarrativeKind}, its 1-2
     * sentences, and — on the needs-attention kinds only — the deterministic
     * tracking-data reason (null elsewhere and on rows written before it).
     */
    public record NarrativeItemDto(String kind, String text, String reason) {
    }
}
