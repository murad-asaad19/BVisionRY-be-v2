package com.bvisionry.comparison.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Write bodies for the narrative review gate (spec §6). */
public final class NarrativeRequests {

    private NarrativeRequests() {
    }

    /** On-demand generation for one mapped pillar ("Generate for another pillar…"). */
    public record GenerateNarrativeRequest(@NotNull UUID distancePillarId) {
    }

    /**
     * A reviewer's edit. {@code kind} is optional — leaving it null keeps the
     * model's classification and edits only the prose.
     */
    public record UpdateNarrativeRequest(@NotBlank String body, String kind, String closingAction) {
    }
}
