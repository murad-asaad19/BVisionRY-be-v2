package com.bvisionry.comparison.domain;

import java.util.Optional;

/**
 * The shift-narrative classifications (spec §6). Each is exactly one
 * before → after transition between a "strength" and a "growth edge", so the set
 * is the 3x3 matrix of those two states plus "not present" — REGRESSED and
 * EMERGED (V202) fill the two cells that had no kind and were being mis-filed
 * as FADED and as a closing action.
 *
 * "Decline" is deliberately NOT here: it is a shift <em>band</em>, never a
 * classification — a declining pillar can carry any of these kinds, and what
 * the decline changes is that the narrative must close with a forward-looking
 * next step.
 */
public enum NarrativeKind {
    /** An issue named in the before text is no longer present. */
    RESOLVED,
    /** A strength named in the before text is still present and has deepened. */
    CARRIED_FORWARD,
    /** Something in the after text has no equivalent in the before text. */
    NEW,
    /** An issue named in the before text is still present. */
    PERSISTED,
    /** A strength named in the before text no longer appears. */
    FADED,
    /** A strength named in the before text is now named as a growth edge. */
    REGRESSED,
    /** A growth edge in the after text with no equivalent in the before text. */
    EMERGED;

    /**
     * Lenient parse of a model-returned kind — empty when the value is not one
     * of these, which is the signal to re-ask the model. This is the REAL gate on
     * what the AI may return: the JSON schema types "kind" as a free string.
     * Tolerates casing and the hyphen/space spellings a model reaches for
     * ("carried forward").
     */
    public static Optional<NarrativeKind> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (NarrativeKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
