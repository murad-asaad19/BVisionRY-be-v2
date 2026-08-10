package com.bvisionry.programflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Rename only. Lifecycle no longer travels through the generic update —
 * launch/complete/archive are dedicated transitions (spec §8: launching
 * consumes quota, so it must be an explicit, guarded action).
 */
public record UpdateCohortRequest(
        @NotBlank @Size(max = 200) String name) {
}
