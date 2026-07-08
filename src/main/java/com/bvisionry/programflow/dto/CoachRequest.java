package com.bvisionry.programflow.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Ask the AI coach for a hint on one player step (optionally with the learner's draft). */
public record CoachRequest(@NotNull UUID fieldId, @Size(max = 4000) String draft) {
}
