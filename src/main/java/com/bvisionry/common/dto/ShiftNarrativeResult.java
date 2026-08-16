package com.bvisionry.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The Qualitative Shift Narrative job's model output (spec §6): one kind
 * classification, the narrative prose, and the forward-looking next step.
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
        @JsonProperty("kind") String kind,
        @JsonProperty("narrative") String narrative,
        @JsonProperty("closingAction") String closingAction) {

    public ShiftNarrativeResult {
        if (kind == null) kind = "";
        if (narrative == null) narrative = "";
        if (closingAction == null) closingAction = "";
    }
}
