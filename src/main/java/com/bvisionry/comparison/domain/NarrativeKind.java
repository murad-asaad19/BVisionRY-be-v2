package com.bvisionry.comparison.domain;

import java.util.Optional;

/**
 * The five shift-narrative classifications (spec §6). "Decline" is deliberately
 * NOT here: it is a shift <em>band</em>, never a classification — a declining
 * pillar can carry any of these five kinds, and what the decline changes is that
 * the narrative must close with a forward-looking next step.
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
    FADED;

    /**
     * Lenient parse of a model-returned kind — empty when the value is not one
     * of the five, which is the signal to re-ask the model. Tolerates casing and
     * the hyphen/space spellings a model reaches for ("carried forward").
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
