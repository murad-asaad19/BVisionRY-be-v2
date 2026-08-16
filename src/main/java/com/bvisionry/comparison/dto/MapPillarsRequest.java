package com.bvisionry.comparison.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** SUPER_ADMIN: link a baseline pillar to a distance pillar within a cohort's pair. */
public record MapPillarsRequest(
        @NotNull UUID cohortId,
        @NotNull UUID baselinePipelineId,
        @NotNull UUID distancePipelineId,
        @NotNull UUID baselinePillarId,
        @NotNull UUID distancePillarId) {
}
