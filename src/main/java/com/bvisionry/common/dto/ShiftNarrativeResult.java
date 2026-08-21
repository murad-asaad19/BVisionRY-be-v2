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

    /**
     * One observation: what kind of change it is, the sentence or two describing
     * it, and which numbered BEFORE items ({@code B1}, {@code B2}, …) it accounts
     * for — the "nothing disappears" rule's bookkeeping. Empty is legal (a NEW or
     * EMERGED observation has no earlier counterpart); an id the prompt never
     * issued is simply ignored.
     */
    public record Item(@JsonProperty("kind") String kind, @JsonProperty("text") String text,
                       @JsonProperty("covers") List<String> covers) {

        public Item {
            if (kind == null) kind = "";
            if (text == null) text = "";
            covers = covers == null ? List.of()
                    : covers.stream().filter(c -> c != null && !c.isBlank()).toList();
        }

        public Item(String kind, String text) {
            this(kind, text, List.of());
        }
    }

    public ShiftNarrativeResult {
        items = items == null ? List.of() : items.stream().filter(i -> i != null).toList();
        if (closingAction == null) closingAction = "";
    }
}
