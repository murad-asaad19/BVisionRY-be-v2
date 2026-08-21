package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The member-level growth summary's model output (spec §3): the founder's
 * whole cohort read as ONE breakdown across the same kinds the per-pillar
 * narratives use, synthesised from their approved per-pillar observations.
 *
 * <p>Two shapes on purpose. {@code items} is what the shipped prompt asks for.
 * {@code summary} is the pre-breakdown single paragraph — installations whose
 * admins customised this template still ask for it, and V193 deliberately does
 * not rewrite their wording, so a response carrying only prose must still
 * parse. The service stores whichever arrived; it never invents a split nobody
 * made.
 *
 * <p>{@code kind} stays a raw String for the same reason it does on
 * {@link ShiftNarrativeResult}: an unrecognised value must be DETECTED by the
 * code-side guardrail, not thrown inside Jackson or silently nulled.
 */
public record MemberGrowthSummaryResult(
        @JsonProperty("items") List<ShiftNarrativeResult.Item> items,
        @JsonProperty("summary") String summary) {

    public MemberGrowthSummaryResult {
        items = items == null ? List.of() : items.stream().filter(i -> i != null).toList();
        if (summary == null) summary = "";
    }
}
