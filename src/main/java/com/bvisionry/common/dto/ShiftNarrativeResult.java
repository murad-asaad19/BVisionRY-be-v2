package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The Qualitative Shift Narrative job's model output (spec §2): a breakdown of
 * observations, each carrying one kind classification, plus the forward-looking
 * next step for the pillar as a whole.
 *
 * <p>{@code kind} stays a raw String on purpose — the whole point of the
 * code-side guardrail is that an unrecognised value is DETECTED and retried,
 * and a typed enum here would either throw inside Jackson or silently null out
 * before the validator ever sees it.
 *
 * <p>{@code closingAction} is optional in the schema and mandatory in CODE for
 * pillars in the decline band — a rule the database cannot express and the
 * prompt cannot be trusted with.
 */
public record ShiftNarrativeResult(
        @JsonProperty("items") List<Item> items,
        @JsonProperty("closingAction") String closingAction) {

    /** One observation: what kind of change it is, and the sentence or two describing it. */
    public record Item(@JsonProperty("kind") String kind, @JsonProperty("text") String text) {

        public Item {
            if (kind == null) kind = "";
            if (text == null) text = "";
        }
    }

    public ShiftNarrativeResult {
        items = items == null ? List.of() : items.stream().filter(i -> i != null).toList();
        if (closingAction == null) closingAction = "";
    }
}
