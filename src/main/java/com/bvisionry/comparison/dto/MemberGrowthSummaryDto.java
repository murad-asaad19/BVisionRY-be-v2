package com.bvisionry.comparison.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One founder's overall growth summary as the review UI reads it (spec §3).
 * Same §7b stamping contract as {@link ShiftNarrativeDto}: generated / approved
 * / edited each carry their timestamp and the actor's name, and the names go
 * null after that reviewer's GDPR erasure.
 *
 * <p>The member never sees this shape — their payload carries the approved
 * content only ({@code FounderComparisonDto.growthSummary} /
 * {@code growthSummaryItems}).
 *
 * <p><strong>Two shapes, forever</strong>, exactly as {@link ShiftNarrativeDto}
 * carries: {@code items} is the V193 breakdown, {@code body} the
 * pre-breakdown paragraph. Exactly one side is populated, so a reader falls
 * back: {@code items ?? [body]}.
 */
public record MemberGrowthSummaryDto(
        UUID id,
        /**
         * The overall breakdown — null on a pre-V193 row (read {@code body}).
         * Deliberately the SAME item type the per-pillar narratives use: the
         * two render identically, so one shape is one contract to pin.
         */
        List<ShiftNarrativeDto.NarrativeItemDto> items,
        /** Legacy single paragraph — null on everything written since V193. */
        String body,
        String status,
        Instant generatedAt,
        UUID approvedBy,
        String approvedByName,
        Instant approvedAt,
        UUID editedBy,
        String editedByName,
        Instant editedAt) {
}
