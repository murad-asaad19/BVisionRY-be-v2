package com.bvisionry.comparison.dto;

import java.time.Instant;
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
 * <p>{@code closingAction} is separate from {@code body} so the member card can
 * render the mandatory "Next step:" footer on a decline without string-slicing
 * the prose (which is exactly what the design prototype had to do).
 */
public record ShiftNarrativeDto(
        UUID id,
        UUID baselinePillarId,
        UUID distancePillarId,
        String pillarName,
        String kind,
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
        String status,
        Instant generatedAt,
        UUID approvedBy,
        String approvedByName,
        Instant approvedAt,
        UUID editedBy,
        String editedByName,
        Instant editedAt) {
}
